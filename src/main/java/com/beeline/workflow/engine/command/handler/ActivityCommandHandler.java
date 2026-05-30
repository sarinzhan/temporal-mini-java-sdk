package com.beeline.workflow.engine.command.handler;

import com.beeline.workflow.core.config.ActivityOptions;
import com.beeline.workflow.core.config.RetryPolicy;
import com.beeline.workflow.core.exception.ActivityFailureException;
import com.beeline.workflow.core.exception.ActivityTimeoutException;
import com.beeline.workflow.engine.command.ActivityCommand;
import com.beeline.workflow.engine.command.CommandContext;
import com.beeline.workflow.engine.command.CommandHandler;
import com.beeline.workflow.engine.context.WorkflowContextHolder;
import com.beeline.workflow.engine.replay.ActivityReplay;
import com.beeline.workflow.engine.replay.CommandType;
import com.beeline.workflow.engine.replay.LockLostException;
import com.beeline.workflow.engine.replay.ReplayState;
import com.beeline.workflow.engine.replay.WorkflowParkedException;
import com.beeline.workflow.engine.retry.RetryDecider;
import com.beeline.workflow.engine.retry.RetryDecision;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiFunction;

public final class ActivityCommandHandler implements CommandHandler<ActivityCommand> {

    private static final Logger log = LoggerFactory.getLogger(ActivityCommandHandler.class);

    /** How long to park a workflow when the activity pool rejects a submission (saturation backpressure). */
    private static final Duration BACKPRESSURE_RETRY_DELAY = Duration.ofSeconds(1);

    private final RetryDecider retryDecider;
    private final BiFunction<String, ActivityOptions, ActivityOptions> optionsResolver;
    private final ExecutorService invocationPool;

    public ActivityCommandHandler(RetryDecider retryDecider) {
        this(retryDecider, (name, opts) -> opts, 64);
    }

    public ActivityCommandHandler(RetryDecider retryDecider,
                                  BiFunction<String, ActivityOptions, ActivityOptions> optionsResolver) {
        this(retryDecider, optionsResolver, 64);
    }

    public ActivityCommandHandler(RetryDecider retryDecider,
                                  BiFunction<String, ActivityOptions, ActivityOptions> optionsResolver,
                                  int maxThreads) {
        this.retryDecider = retryDecider;
        this.optionsResolver = optionsResolver;
        this.invocationPool = newBoundedPool(maxThreads);
    }

    /**
     * Bounded pool with a stable thread count and a capped queue. When the pool and queue are both
     * saturated, {@link java.util.concurrent.ThreadPoolExecutor.AbortPolicy} rejects the submission
     * with {@link RejectedExecutionException}. The handler turns that into a short workflow park
     * (see {@code parkForBackpressure}) rather than running the activity inline on the worker thread:
     * {@code CallerRunsPolicy} would have done the latter, silently breaking the start-to-close
     * timeout (an inline run can't be cancelled) and blocking the worker that should be polling.
     */
    private static ExecutorService newBoundedPool(int maxThreads) {
        int threads = maxThreads > 0 ? maxThreads : 64;
        var counter = new java.util.concurrent.atomic.AtomicLong();
        ThreadFactory factory = r -> {
            Thread t = new Thread(r, "wf-activity-" + counter.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
        ThreadPoolExecutor pool = new ThreadPoolExecutor(
                threads, threads,
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(threads * 16),
                factory,
                new ThreadPoolExecutor.AbortPolicy());
        pool.allowCoreThreadTimeOut(true);
        return pool;
    }

    @Override
    public Class<ActivityCommand> commandClass() {
        return ActivityCommand.class;
    }

    @Override
    public Object handle(ActivityCommand cmd, CommandContext ctx) {
        ReplayState state = ctx.replayState();
        Long workflowId = ctx.workflowId();

        int seq = state.nextSeq();
        String display = (cmd.name() != null && !cmd.name().isBlank()) ? cmd.name() : ("activity#" + seq);
        ActivityOptions options = optionsResolver.apply(display,
                cmd.options() != null ? cmd.options() : ActivityOptions.defaultOptions());

        // Non-determinism guard.
        state.assertCommandTypeMatches(seq, CommandType.ACTIVITY);

        Optional<ActivityReplay> replay = state.findActivityResult(seq);
        if (replay.isPresent()) {
            ActivityReplay r = replay.get();
            if (r instanceof ActivityReplay.Completed completed) {
                log.debug("[{}/{}] activity replay-cached seq={}", workflowId, display, seq);
                return ctx.codec().decodeActivityResult(completed.payload(), cmd.returnType());
            }
            if (r instanceof ActivityReplay.Failed failed) {
                throw new ActivityFailureException(display, failed.attempt(), failed.reason(),
                        new RuntimeException(failed.reason()));
            }
        }

        int attempt = state.countUsedActivityAttempts(seq) + 1;
        RetryPolicy policy = options.getRetryPolicy() != null ? options.getRetryPolicy() : RetryPolicy.defaultPolicy();

        // Reserve a pool slot BEFORE recording ACTIVITY_STARTED. If the pool is saturated the submit
        // is rejected and the activity never runs, so we must not burn an attempt on it — park instead.
        Future<Object> future;
        try {
            future = invocationPool.submit(cmd.body()::get);
        } catch (RejectedExecutionException rejected) {
            return parkForBackpressure(ctx, workflowId, seq, display, attempt);
        }

        ctx.eventLog().activityStarted(seq, display, attempt);
        log.info("[{}/{}] activity running seq={} attempt={}", workflowId, display, seq, attempt);

        Object result;
        try {
            result = awaitWithTimeout(future, options.getStartToCloseTimeout());
        } catch (LockLostException lost) {
            throw lost;
        } catch (Throwable cause) {
            return failOrRetry(ctx, workflowId, seq, display, attempt, policy, cause);
        }
        ctx.eventLog().activityCompleted(seq, display, attempt, result);
        log.info("[{}/{}] activity COMPLETED seq={} attempt={}", workflowId, display, seq, attempt);
        return result;
    }

    /**
     * The activity pool was saturated, so the activity has not run. Park the workflow and schedule a
     * short-delay wake-up WITHOUT recording an ACTIVITY_STARTED — so this rejected slot does not count
     * toward {@code maxAttempts} (which counts STARTED events). The next turn re-runs the same attempt
     * once capacity frees. This is natural backpressure that never fails a workflow for being busy.
     */
    private Object parkForBackpressure(CommandContext ctx, Long workflowId, int seq, String display, int attempt) {
        if (ctx.taskLease().isLost()) {
            throw new LockLostException(ctx.taskLease().taskId(), ctx.taskLease().token());
        }
        Instant fireAt = retryDecider.computeFireAt(BACKPRESSURE_RETRY_DELAY);
        ctx.eventLog().activityRetryScheduled(seq, display, attempt, fireAt, "activity pool saturated (backpressure)");
        log.warn("[{}/{}] activity pool saturated seq={} attempt={} — parked for backpressure until {}",
                workflowId, display, seq, attempt, fireAt);
        throw new WorkflowParkedException(seq);
    }

    /** Always throws — either {@link WorkflowParkedException} (retry) or {@link ActivityFailureException}. */
    private Object failOrRetry(CommandContext ctx, Long workflowId, int seq, String display,
                               int attempt, RetryPolicy policy, Throwable cause) {
        if (ctx.taskLease().isLost()) {
            throw new LockLostException(ctx.taskLease().taskId(), ctx.taskLease().token());
        }
        RetryDecision decision = retryDecider.decide(cause, attempt, policy);
        if (decision instanceof RetryDecision.Retry retry) {
            var fireAt = retryDecider.computeFireAt(retry.delay());
            ctx.eventLog().activityRetryScheduled(seq, display, attempt, fireAt, safeMessage(cause));
            log.warn("[{}/{}] activity FAILED seq={} attempt={} — retry parked until {}",
                    workflowId, display, seq, attempt, fireAt);
            throw new WorkflowParkedException(seq);
        }
        boolean nonRetryable = decision instanceof RetryDecision.Terminal t && t.nonRetryable();
        ctx.eventLog().activityFailed(seq, display, attempt, safeMessage(cause));
        log.error("[{}/{}] activity FAILED seq={} attempt={} — {}",
                workflowId, display, seq, attempt, nonRetryable ? "non-retryable" : "not retryable / exhausted");
        throw new ActivityFailureException(display, attempt, safeMessage(cause), cause);
    }

    private Object awaitWithTimeout(Future<Object> future, Duration timeout) throws Throwable {
        long timeoutMs = (timeout != null && !timeout.isNegative()) ? timeout.toMillis() : 0L;
        // Use the raw Future from the pool (NOT CompletableFuture): Future.cancel(true) actually
        // interrupts the worker thread, so an activity blocked on I/O or one that checks the interrupt
        // flag is genuinely stopped on timeout — CompletableFuture.cancel would merely abandon it and
        // leak the thread. Purely CPU-bound activities that ignore interrupts still can't be forced
        // down; that is a JVM limitation, but this is strictly better than before.
        try {
            if (timeoutMs <= 0) {
                return future.get();
            }
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException te) {
            future.cancel(true);
            throw new ActivityTimeoutException("activity", Duration.ofMillis(timeoutMs));
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            // Interrupted because our lease was lost — surface as lock-lost.
            CommandContext c = WorkflowContextHolder.current();
            if (c != null) c.taskLease().assertOwned();
            throw ie;
        } catch (ExecutionException ee) {
            throw ee.getCause() != null ? ee.getCause() : ee;
        } catch (CompletionException ce) {
            throw ce.getCause() != null ? ce.getCause() : ce;
        }
    }

    private static String safeMessage(Throwable t) {
        return t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
    }
}
