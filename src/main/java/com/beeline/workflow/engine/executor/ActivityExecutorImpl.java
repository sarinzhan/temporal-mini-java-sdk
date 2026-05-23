package com.beeline.workflow.engine.executor;

import com.beeline.workflow.core.api.WorkflowContext;
import com.beeline.workflow.core.config.ActivityOptions;
import com.beeline.workflow.core.config.RetryPolicy;
import com.beeline.workflow.core.exception.ActivityFailureException;
import com.beeline.workflow.core.exception.ActivityTimeoutException;
import com.beeline.workflow.core.exception.NonRetryableException;
import com.beeline.workflow.core.model.Event;
import com.beeline.workflow.core.model.EventType;
import com.beeline.workflow.core.model.RetryRecord;
import com.beeline.workflow.engine.context.WorkflowContextHolder;
import com.beeline.workflow.engine.context.WorkflowContextImpl;
import com.beeline.workflow.engine.replay.CommandType;
import com.beeline.workflow.engine.replay.HistoryCursor;
import com.beeline.workflow.engine.replay.QueryReplayBlockedException;
import com.beeline.workflow.persistence.repository.EventRepository;
import com.beeline.workflow.persistence.repository.RetryRepository;
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
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * Event-sourced activity executor. On each invocation:
 *   1. Take the next seq from the workflow's {@link HistoryCursor}.
 *   2. If history contains ACTIVITY_COMPLETED / ACTIVITY_FAILED for that seq, return / re-throw it
 *      without running the activity again (replay).
 *   3. Otherwise, write ACTIVITY_SCHEDULED (if not already in history), execute the activity,
 *      and write ACTIVITY_COMPLETED on success or ACTIVITY_RETRY_SCHEDULED / ACTIVITY_FAILED on failure.
 */
public class ActivityExecutorImpl implements ActivityExecutor {

    private static final Logger log = LoggerFactory.getLogger(ActivityExecutorImpl.class);

    private static final Set<EventType> ACTIVITY_TERMINAL = Set.of(
            EventType.ACTIVITY_COMPLETED, EventType.ACTIVITY_FAILED);

    private final EventRepository eventRepository;
    private final RetryRepository retryRepository;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;
    private final ExecutorService activityThreadPool;
    private final java.util.function.BiFunction<String, ActivityOptions, ActivityOptions> optionsResolver;

    public ActivityExecutorImpl(EventRepository eventRepository,
                                RetryRepository retryRepository,
                                ObjectMapper objectMapper,
                                PlatformTransactionManager transactionManager) {
        this(eventRepository, retryRepository, objectMapper, transactionManager, (name, opts) -> opts);
    }

    public ActivityExecutorImpl(EventRepository eventRepository,
                                RetryRepository retryRepository,
                                ObjectMapper objectMapper,
                                PlatformTransactionManager transactionManager,
                                java.util.function.BiFunction<String, ActivityOptions, ActivityOptions> optionsResolver) {
        this.eventRepository = eventRepository;
        this.retryRepository = retryRepository;
        this.objectMapper = objectMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.optionsResolver = optionsResolver;
        this.activityThreadPool = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "wf-activity-timeout-" + System.nanoTime());
            t.setDaemon(true);
            return t;
        });
    }

    @Override
    public Object execute(String activityName,
                          ActivityOptions options,
                          Type returnType,
                          Supplier<Object> invocation) {
        WorkflowContext ctx = WorkflowContextHolder.require();
        Long workflowId = ctx.getWorkflowId();
        HistoryCursor cursor = ctx.getHistoryCursor();
        if (cursor == null) {
            throw new IllegalStateException("HistoryCursor missing — workflow context not initialized for replay");
        }
        options = optionsResolver.apply(activityName, options);

        int seq = cursor.nextSeq();

        Optional<Event> terminal = cursor.findCompletion(seq, CommandType.ACTIVITY, ACTIVITY_TERMINAL);
        if (terminal.isPresent()) {
            Event e = terminal.get();
            if (e.getEventType() == EventType.ACTIVITY_COMPLETED) {
                log.debug("[{}/{}] activity replay-cached seq={}", workflowId, activityName, seq);
                return deserializeResult(e.getPayload(), returnType);
            }
            // ACTIVITY_FAILED in history → check if a manual ACTIVITY_RETRY_SCHEDULED came after (admin override).
            boolean forceRetry = cursor.findBySeqAndType(seq, EventType.ACTIVITY_RETRY_SCHEDULED).isPresent()
                    && hasManualMarker(cursor, seq);
            if (!forceRetry) {
                String reason = e.getPayload() != null ? e.getPayload() : "activity failed";
                throw new ActivityFailureException(activityName, attemptFromHistory(cursor, seq),
                        reason, new RuntimeException(reason));
            }
            log.info("[{}/{}] manual force-retry — re-executing seq={}", workflowId, activityName, seq);
        }

        if (cursor.isQueryMode()) {
            throw new QueryReplayBlockedException(
                    "activity " + activityName + " not yet recorded in history (seq=" + seq + ")");
        }

        int attempt = attemptFromHistory(cursor, seq) + 1;

        if (cursor.findBySeqAndType(seq, EventType.ACTIVITY_SCHEDULED).isEmpty()) {
            saveEvent(workflowId, EventType.ACTIVITY_SCHEDULED, seq, activityName,
                    "{\"attempt\":" + attempt + "}");
        }
        saveEvent(workflowId, EventType.ACTIVITY_STARTED, seq, activityName,
                "{\"attempt\":" + attempt + "}");

        Object result;
        Duration timeout = options.getStartToCloseTimeout();
        try {
            CompletableFuture<Object> future = CompletableFuture.supplyAsync(invocation, activityThreadPool);
            if (timeout == null || timeout.isZero() || timeout.isNegative()) {
                result = future.join();
            } else {
                result = future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            }
        } catch (TimeoutException te) {
            return failOrRetry(workflowId, activityName, seq, attempt, options,
                    new ActivityTimeoutException(activityName, timeout));
        } catch (ExecutionException ee) {
            Throwable cause = ee.getCause() != null ? ee.getCause() : ee;
            return failOrRetry(workflowId, activityName, seq, attempt, options, cause);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return failOrRetry(workflowId, activityName, seq, attempt, options, ie);
        } catch (RuntimeException re) {
            return failOrRetry(workflowId, activityName, seq, attempt, options, re);
        }

        String payload = buildCompletedPayload(result, attempt);
        saveEvent(workflowId, EventType.ACTIVITY_COMPLETED, seq, activityName, payload);
        log.info("[{}/{}] activity COMPLETED seq={} attempt={}", workflowId, activityName, seq, attempt);
        return result;
    }

    private boolean hasManualMarker(HistoryCursor cursor, int seq) {
        return cursor.findBySeqAndType(seq, EventType.ACTIVITY_RETRY_SCHEDULED)
                .map(Event::getPayload)
                .filter(p -> p != null && p.contains("\"manual\":true"))
                .isPresent();
    }

    private int attemptFromHistory(HistoryCursor cursor, int seq) {
        Optional<Event> sched = cursor.findBySeqAndType(seq, EventType.ACTIVITY_SCHEDULED);
        Optional<Event> retry = cursor.findBySeqAndType(seq, EventType.ACTIVITY_RETRY_SCHEDULED);
        int n = 0;
        if (sched.isPresent()) n = extractInt(sched.get().getPayload(), "attempt", 1);
        if (retry.isPresent()) n = Math.max(n, extractInt(retry.get().getPayload(), "attempt", n));
        return n;
    }

    private Object failOrRetry(Long workflowId,
                               String activityName,
                               int seq,
                               int attempt,
                               ActivityOptions options,
                               Throwable cause) {
        RetryPolicy policy = options.getRetryPolicy() != null ? options.getRetryPolicy() : RetryPolicy.defaultPolicy();
        boolean noRetry = cause instanceof NonRetryableException || policy.isNoRetry(cause);
        boolean exhausted = attempt >= policy.getMaxAttempts();
        boolean willRetry = !noRetry && !exhausted;
        Instant fireAt = willRetry ? Instant.now().plus(policy.nextDelay(attempt)) : null;

        if (willRetry) {
            transactionTemplate.executeWithoutResult(s -> {
                RetryRecord r = new RetryRecord();
                r.setWorkflowId(workflowId);
                r.setActivityName(activityName);
                r.setAttempt(attempt);
                r.setMaxAttempts(policy.getMaxAttempts());
                r.setFireAt(fireAt);
                r.setReason(safeMessage(cause));
                r.setProcessed(false);
                WorkflowContext ctx = WorkflowContextHolder.current();
                if (ctx instanceof WorkflowContextImpl impl && impl.getCurrentTaskId() != null) {
                    r.setTaskId(impl.getCurrentTaskId());
                }
                retryRepository.save(r);
            });
            String payload = "{\"attempt\":" + attempt + ",\"fireAt\":\"" + fireAt
                    + "\",\"reason\":\"" + escapeJson(safeMessage(cause)) + "\"}";
            saveEvent(workflowId, EventType.ACTIVITY_RETRY_SCHEDULED, seq, activityName, payload);
            log.warn("[{}/{}] activity FAILED seq={} attempt={} — retrying at {}",
                    workflowId, activityName, seq, attempt, fireAt);
        } else {
            String payload = "{\"attempt\":" + attempt
                    + ",\"reason\":\"" + escapeJson(safeMessage(cause)) + "\""
                    + ",\"terminal\":true}";
            saveEvent(workflowId, EventType.ACTIVITY_FAILED, seq, activityName, payload);
            log.error("[{}/{}] activity FAILED seq={} attempt={} — {}",
                    workflowId, activityName, seq, attempt, noRetry ? "non-retryable" : "attempts exhausted");
        }

        throw new ActivityFailureException(activityName, attempt, safeMessage(cause), cause);
    }

    private void saveEvent(Long workflowId, EventType type, int seq, String activityName, String payload) {
        transactionTemplate.executeWithoutResult(s -> {
            Event e = new Event();
            e.setWorkflowId(workflowId);
            e.setEventType(type);
            e.setCommandType(CommandType.ACTIVITY.name());
            e.setSeq(seq);
            e.setActivityName(activityName);
            e.setPayload(payload);
            eventRepository.save(e);
        });
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
