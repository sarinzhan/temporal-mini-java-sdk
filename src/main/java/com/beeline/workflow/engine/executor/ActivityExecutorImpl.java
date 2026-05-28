package com.beeline.workflow.engine.executor;

import com.beeline.workflow.core.api.WorkflowContext;
import com.beeline.workflow.core.config.ActivityOptions;
import com.beeline.workflow.core.config.RetryPolicy;
import com.beeline.workflow.core.exception.ActivityFailureException;
import com.beeline.workflow.core.exception.ActivityTimeoutException;
import com.beeline.workflow.core.exception.NonRetryableException;
import com.beeline.workflow.core.model.Event;
import com.beeline.workflow.core.model.EventType;
import com.beeline.workflow.core.model.Schedule;
import com.beeline.workflow.engine.context.WorkflowContextHolder;
import com.beeline.workflow.engine.replay.CommandType;
import com.beeline.workflow.engine.replay.HistoryCursor;
import com.beeline.workflow.engine.replay.LockLostException;
import com.beeline.workflow.engine.replay.WorkflowParkedException;
import com.beeline.workflow.persistence.repository.EventRepository;
import com.beeline.workflow.persistence.repository.ScheduleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.lang.reflect.Type;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiFunction;
import java.util.function.Supplier;

/**
 * Inline, single-thread activity executor (see {@link ActivityExecutor}). The activity body runs on
 * the workflow thread; only the wait <i>between</i> retry attempts is parked (the turn ends and a
 * {@code wflow.schedule} row drives the wake-up), so a long backoff never blocks a worker slot.
 */
public class ActivityExecutorImpl implements ActivityExecutor {

    private static final Logger log = LoggerFactory.getLogger(ActivityExecutorImpl.class);

    private static final Set<EventType> ACTIVITY_TERMINAL = Set.of(
            EventType.ACTIVITY_COMPLETED, EventType.ACTIVITY_FAILED);

    private final EventRepository eventRepository;
    private final ScheduleRepository scheduleRepository;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;
    private final BiFunction<String, ActivityOptions, ActivityOptions> optionsResolver;
    private final ExecutorService invocationPool;

    public ActivityExecutorImpl(EventRepository eventRepository,
                                ScheduleRepository scheduleRepository,
                                ObjectMapper objectMapper,
                                PlatformTransactionManager transactionManager) {
        this(eventRepository, scheduleRepository, objectMapper, transactionManager, (name, opts) -> opts);
    }

    public ActivityExecutorImpl(EventRepository eventRepository,
                                ScheduleRepository scheduleRepository,
                                ObjectMapper objectMapper,
                                PlatformTransactionManager transactionManager,
                                BiFunction<String, ActivityOptions, ActivityOptions> optionsResolver) {
        this.eventRepository = eventRepository;
        this.scheduleRepository = scheduleRepository;
        this.objectMapper = objectMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.optionsResolver = optionsResolver;
        this.invocationPool = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "wf-activity-" + System.nanoTime());
            t.setDaemon(true);
            return t;
        });
    }

    @Override
    public Object execute(String name, ActivityOptions options, Type returnType, Supplier<Object> body) {
        WorkflowContext ctx = WorkflowContextHolder.require();
        Long workflowId = ctx.getWorkflowId();
        HistoryCursor cursor = ctx.getHistoryCursor();
        if (cursor == null) {
            throw new IllegalStateException("HistoryCursor missing — workflow context not initialized for replay");
        }

        int seq = cursor.nextSeq();
        String display = (name != null && !name.isBlank()) ? name : ("activity#" + seq);
        options = optionsResolver.apply(display, options != null ? options : ActivityOptions.defaultOptions());

        // Non-determinism guard: throws if a recorded event at this seq has a different command type.
        cursor.findCompletion(seq, CommandType.ACTIVITY, ACTIVITY_TERMINAL);

        Event latestEvent = cursor.latestEventForSeq(seq).orElse(null);
        EventType latest = latestEvent != null ? latestEvent.getEventType() : null;

        if (latest == EventType.ACTIVITY_COMPLETED) {
            log.debug("[{}/{}] activity replay-cached seq={}", workflowId, display, seq);
            return deserializeResult(latestEvent.getPayload(), returnType);
        }
        if (latest == EventType.ACTIVITY_FAILED) {
            String reason = latestEvent.getPayload() != null ? latestEvent.getPayload() : "activity failed";
            throw new ActivityFailureException(display, attemptFromHistory(cursor, seq), reason,
                    new RuntimeException(reason));
        }

        // Fresh attempt (or resuming after a parked retry / mid-run crash): run the body inline.
        int attempt = countStarted(cursor, seq) + 1;
        RetryPolicy policy = options.getRetryPolicy() != null ? options.getRetryPolicy() : RetryPolicy.defaultPolicy();
        recordStarted(workflowId, seq, display, attempt);
        log.info("[{}/{}] activity running seq={} attempt={}", workflowId, display, seq, attempt);

        Object result;
        try {
            result = invokeWithTimeout(body, options.getStartToCloseTimeout());
        } catch (LockLostException lost) {
            throw lost;  // lease lost — discard the turn
        } catch (Throwable cause) {
            return failOrRetry(ctx, workflowId, seq, display, attempt, policy, cause);
        }
        recordCompleted(workflowId, seq, display, attempt, result);
        log.info("[{}/{}] activity COMPLETED seq={} attempt={}", workflowId, display, seq, attempt);
        return result;
    }

    /** Always returns by throwing — either {@link WorkflowParkedException} (retry) or {@link ActivityFailureException}. */
    private Object failOrRetry(WorkflowContext ctx, Long workflowId, int seq, String display,
                               int attempt, RetryPolicy policy, Throwable cause) {
        if (ctx.getTaskLease().isLost()) {
            throw new LockLostException(ctx.getTaskLease().taskId(), ctx.getTaskLease().token());
        }
        boolean nonRetryable = cause instanceof NonRetryableException;
        boolean retry = !nonRetryable && policy.isRetryable(cause) && attempt < policy.getMaxAttempts();
        if (retry) {
            Instant fireAt = Instant.now().plus(policy.nextDelay(attempt));
            recordRetryScheduled(workflowId, seq, display, attempt, fireAt, cause);
            log.warn("[{}/{}] activity FAILED seq={} attempt={} — retry parked until {}",
                    workflowId, display, seq, attempt, fireAt);
            throw new WorkflowParkedException(seq);
        }
        recordTerminalFailure(workflowId, seq, display, attempt, cause);
        log.error("[{}/{}] activity FAILED seq={} attempt={} — {}",
                workflowId, display, seq, attempt, nonRetryable ? "non-retryable" : "not retryable / exhausted");
        throw new ActivityFailureException(display, attempt, safeMessage(cause), cause);
    }

    // ── invocation ───────────────────────────────────────────────────────────

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
            // Interrupted because our lease was lost — surface as lock-lost so the turn is discarded.
            WorkflowContext c = WorkflowContextHolder.current();
            if (c != null) c.getTaskLease().assertOwned();
            throw ie;
        } catch (ExecutionException ee) {
            throw ee.getCause() != null ? ee.getCause() : ee;
        } catch (CompletionException ce) {
            throw ce.getCause() != null ? ce.getCause() : ce;
        }
    }

    // ── history writes (all fenced by the task lease) ──────────────────────────

    private void recordStarted(Long workflowId, int seq, String name, int attempt) {
        assertOwned();
        transactionTemplate.executeWithoutResult(s ->
                eventRepository.save(activityEvent(workflowId, EventType.ACTIVITY_STARTED, seq, name,
                        "{\"attempt\":" + attempt + "}")));
    }

    private void recordCompleted(Long workflowId, int seq, String name, int attempt, Object result) {
        String payload = buildCompletedPayload(result, attempt);
        assertOwned();
        transactionTemplate.executeWithoutResult(s ->
                eventRepository.save(activityEvent(workflowId, EventType.ACTIVITY_COMPLETED, seq, name, payload)));
    }

    private void recordTerminalFailure(Long workflowId, int seq, String name, int attempt, Throwable cause) {
        assertOwned();
        transactionTemplate.executeWithoutResult(s ->
                eventRepository.save(activityEvent(workflowId, EventType.ACTIVITY_FAILED, seq, name,
                        "{\"attempt\":" + attempt + ",\"reason\":\"" + escapeJson(safeMessage(cause))
                                + "\",\"terminal\":true}")));
    }

    /**
     * Atomically record {@code ACTIVITY_RETRY_SCHEDULED} and the {@code wflow.schedule} row in one
     * transaction, so a crash leaves either both (the scheduler wakes the workflow) or neither (the
     * task reclaim re-runs it) — never an event without a wake-up to drive the retry.
     */
    private void recordRetryScheduled(Long workflowId, int seq, String name, int attempt,
                                      Instant fireAt, Throwable cause) {
        assertOwned();
        transactionTemplate.executeWithoutResult(s -> {
            eventRepository.save(activityEvent(workflowId, EventType.ACTIVITY_RETRY_SCHEDULED, seq, name,
                    "{\"attempt\":" + attempt + ",\"fireAt\":\"" + fireAt
                            + "\",\"reason\":\"" + escapeJson(safeMessage(cause)) + "\"}"));
            Schedule sched = new Schedule();
            sched.setWorkflowId(workflowId);
            sched.setSeq(seq);
            sched.setFireAt(fireAt);
            sched.setReason("retry " + name + " attempt " + attempt);
            sched.setProcessed(false);
            scheduleRepository.save(sched);
        });
    }

    private Event activityEvent(Long workflowId, EventType type, int seq, String name, String payload) {
        Event e = new Event();
        e.setWorkflowId(workflowId);
        e.setEventType(type);
        e.setCommandType(CommandType.ACTIVITY.name());
        e.setSeq(seq);
        e.setActivityName(name);
        e.setPayload(payload);
        return e;
    }

    /** Fence: refuse to write if the workflow's task lease was lost to another node. */
    private void assertOwned() {
        WorkflowContext c = WorkflowContextHolder.current();
        if (c != null) c.getTaskLease().assertOwned();
    }

    // ── history reads ───────────────────────────────────────────────────────────

    /** Number of attempts already started for this seq (each attempt writes one ACTIVITY_STARTED). */
    private int countStarted(HistoryCursor cursor, int seq) {
        int n = 0;
        for (Event e : cursor.eventsForSeq(seq)) {
            if (e.getEventType() == EventType.ACTIVITY_STARTED) n++;
        }
        return n;
    }

    /** Highest attempt number recorded for this seq (for the replay-FAILED message). */
    private int attemptFromHistory(HistoryCursor cursor, int seq) {
        int n = 0;
        for (Event e : cursor.eventsForSeq(seq)) {
            EventType t = e.getEventType();
            if (t == EventType.ACTIVITY_STARTED || t == EventType.ACTIVITY_RETRY_SCHEDULED
                    || t == EventType.ACTIVITY_FAILED) {
                n = Math.max(n, extractInt(e.getPayload(), "attempt", n));
            }
        }
        return n;
    }

    private Object deserializeResult(String payload, Type returnType) {
        if (payload == null) return null;
        try {
            JsonNode node = objectMapper.readTree(payload);
            JsonNode resultNode = node.get("result");
            if (resultNode == null || resultNode.isNull()) return null;
            Type effective = returnType;
            if (effective == null || effective == void.class || effective == Void.class) {
                JsonNode typeNode = node.get("resultType");
                if (typeNode != null && !typeNode.isNull()) {
                    try {
                        effective = Class.forName(typeNode.asString());
                    } catch (ClassNotFoundException cnf) {
                        throw new IllegalStateException(
                                "Recorded activity result type not on classpath: " + typeNode.asString(), cnf);
                    }
                } else {
                    effective = Object.class;
                }
            }
            JavaType jt = objectMapper.constructType(effective);
            return objectMapper.readValue(objectMapper.writeValueAsString(resultNode), jt);
        } catch (RuntimeException re) {
            throw re;
        } catch (Exception e) {
            throw new RuntimeException("Failed to deserialize cached activity result", e);
        }
    }

    private String buildCompletedPayload(Object result, int attempt) {
        try {
            String resultJson = result == null ? "null" : objectMapper.writeValueAsString(result);
            String runtimeType = result != null ? result.getClass().getName() : null;
            return "{\"attempt\":" + attempt
                    + ",\"result\":" + resultJson
                    + (runtimeType != null ? ",\"resultType\":\"" + runtimeType + "\"" : "")
                    + "}";
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize activity result", e);
        }
    }

    private int extractInt(String payload, String field, int defaultValue) {
        if (payload == null) return defaultValue;
        try {
            JsonNode node = objectMapper.readTree(payload);
            JsonNode v = node.get(field);
            return v != null && v.isNumber() ? v.asInt() : defaultValue;
        } catch (Exception e) {
            return defaultValue;
        }
    }

    private static String safeMessage(Throwable t) {
        return t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
    }

    private static String escapeJson(String v) {
        if (v == null) return "";
        return v.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
