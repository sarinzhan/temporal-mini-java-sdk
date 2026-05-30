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
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiFunction;
import java.util.function.Supplier;

public final class ActivityCommandHandler implements CommandHandler<ActivityCommand> {

    private static final Logger log = LoggerFactory.getLogger(ActivityCommandHandler.class);

    private final RetryDecider retryDecider;
    private final BiFunction<String, ActivityOptions, ActivityOptions> optionsResolver;
    private final ExecutorService invocationPool;

    public ActivityCommandHandler(RetryDecider retryDecider) {
        this(retryDecider, (name, opts) -> opts);
    }

    public ActivityCommandHandler(RetryDecider retryDecider,
                                  BiFunction<String, ActivityOptions, ActivityOptions> optionsResolver) {
        this.retryDecider = retryDecider;
        this.optionsResolver = optionsResolver;
        this.invocationPool = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "wf-activity-" + System.nanoTime());
            t.setDaemon(true);
            return t;
        });
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
        ctx.eventLog().activityStarted(seq, display, attempt);
        log.info("[{}/{}] activity running seq={} attempt={}", workflowId, display, seq, attempt);

        Object result;
        try {
            result = invokeWithTimeout(cmd.body(), options.getStartToCloseTimeout());
        } catch (LockLostException lost) {
            throw lost;
        } catch (Throwable cause) {
            return failOrRetry(ctx, workflowId, seq, display, attempt, policy, cause);
        }
        ctx.eventLog().activityCompleted(seq, display, attempt, result);
        log.info("[{}/{}] activity COMPLETED seq={} attempt={}", workflowId, display, seq, attempt);
        return result;
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

    private Object invokeWithTimeout(Supplier<Object> body, Duration timeout) throws Throwable {
        long timeoutMs = (timeout != null && !timeout.isNegative()) ? timeout.toMillis() : 0L;
        CompletableFuture<Object> future = CompletableFuture.supplyAsync(body, invocationPool);
        try {
            if (timeoutMs <= 0) {
                return future.join();
            }
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException te) {
            future.cancel(true);
            throw new ActivityTimeoutException("activity", Duration.ofMillis(timeoutMs));
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
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
