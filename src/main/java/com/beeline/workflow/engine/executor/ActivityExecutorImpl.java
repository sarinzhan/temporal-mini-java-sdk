package com.beeline.workflow.engine.executor;

import com.beeline.workflow.core.api.WorkflowContext;
import com.beeline.workflow.core.config.ActivityOptions;
import com.beeline.workflow.core.config.RetryPolicy;
import com.beeline.workflow.core.exception.ActivityFailureException;
import com.beeline.workflow.core.model.Event;
import com.beeline.workflow.core.model.EventType;
import com.beeline.workflow.core.model.Task;
import com.beeline.workflow.core.model.TaskStatus;
import com.beeline.workflow.engine.context.WorkflowContextHolder;
import com.beeline.workflow.engine.replay.CommandType;
import com.beeline.workflow.engine.replay.HistoryCursor;
import com.beeline.workflow.engine.replay.QueryReplayBlockedException;
import com.beeline.workflow.engine.replay.WorkflowParkedException;
import com.beeline.workflow.persistence.repository.EventRepository;
import com.beeline.workflow.persistence.repository.TaskRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * Workflow-side activity executor. The activity is <b>not</b> run on the workflow thread.
 * On each invocation, using the workflow's {@link HistoryCursor}:
 *   1. Take the next seq.
 *   2. If history already has ACTIVITY_COMPLETED for that seq, return the cached result (replay).
 *   3. If history has a terminal ACTIVITY_FAILED, re-throw it (replay).
 *   4. If an activity task is already in flight (ACTIVITY_SCHEDULED / ACTIVITY_STARTED), park.
 *   5. Otherwise (first attempt, or ACTIVITY_RETRY_SCHEDULED ready for the next attempt), atomically
 *      write ACTIVITY_SCHEDULED and create an {@code activity} {@link Task}, then park the workflow.
 *
 * The actual invocation happens later on a separate worker via {@link ActivityTaskExecutor}.
 */
public class ActivityExecutorImpl implements ActivityExecutor {

    private static final Logger log = LoggerFactory.getLogger(ActivityExecutorImpl.class);

    private static final Set<EventType> ACTIVITY_TERMINAL = Set.of(
            EventType.ACTIVITY_COMPLETED, EventType.ACTIVITY_FAILED);

    private final EventRepository eventRepository;
    private final TaskRepository taskRepository;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;
    private final java.util.function.BiFunction<String, ActivityOptions, ActivityOptions> optionsResolver;

    public ActivityExecutorImpl(EventRepository eventRepository,
                                TaskRepository taskRepository,
                                ObjectMapper objectMapper,
                                PlatformTransactionManager transactionManager) {
        this(eventRepository, taskRepository, objectMapper, transactionManager, (name, opts) -> opts);
    }

    public ActivityExecutorImpl(EventRepository eventRepository,
                                TaskRepository taskRepository,
                                ObjectMapper objectMapper,
                                PlatformTransactionManager transactionManager,
                                java.util.function.BiFunction<String, ActivityOptions, ActivityOptions> optionsResolver) {
        this.eventRepository = eventRepository;
        this.taskRepository = taskRepository;
        this.objectMapper = objectMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.optionsResolver = optionsResolver;
    }

    @Override
    public Object execute(String activityName,
                          ActivityOptions options,
                          Type returnType,
                          Class<?> activityInterface,
                          Method method,
                          Object[] args) {
        WorkflowContext ctx = WorkflowContextHolder.require();
        Long workflowId = ctx.getWorkflowId();
        HistoryCursor cursor = ctx.getHistoryCursor();
        if (cursor == null) {
            throw new IllegalStateException("HistoryCursor missing — workflow context not initialized for replay");
        }
        options = optionsResolver.apply(activityName, options);

        int seq = cursor.nextSeq();

        // Non-determinism guard: throws if a recorded event at this seq has a different command type
        // (workflow code shape changed). We ignore the returned terminal and decide off the *latest*
        // event instead, because a manual force-retry can record a fresh outcome after an earlier one.
        cursor.findCompletion(seq, CommandType.ACTIVITY, ACTIVITY_TERMINAL);

        Event latestEvent = cursor.latestEventForSeq(seq).orElse(null);
        EventType latest = latestEvent != null ? latestEvent.getEventType() : null;

        // Latest outcome wins. COMPLETED → replay the cached result; FAILED → surface to workflow code.
        if (latest == EventType.ACTIVITY_COMPLETED) {
            log.debug("[{}/{}] activity replay-cached seq={}", workflowId, activityName, seq);
            return deserializeResult(latestEvent.getPayload(), returnType);
        }
        if (latest == EventType.ACTIVITY_FAILED) {
            String reason = latestEvent.getPayload() != null ? latestEvent.getPayload() : "activity failed";
            throw new ActivityFailureException(activityName, attemptFromHistory(cursor, seq),
                    reason, new RuntimeException(reason));
        }

        if (cursor.isQueryMode()) {
            throw new QueryReplayBlockedException(
                    "activity " + activityName + " not yet recorded in history (seq=" + seq + ")");
        }

        if (latest == EventType.ACTIVITY_SCHEDULED || latest == EventType.ACTIVITY_STARTED) {
            // A task for this seq is already pending/running (e.g. the workflow woke for a signal while
            // the activity worker is still busy). Don't create a duplicate — just park again.
            log.debug("[{}/{}] activity already in flight seq={} — parking", workflowId, activityName, seq);
            throw new WorkflowParkedException(WorkflowParkedException.Kind.ACTIVITY, seq);
        }

        // latest == null (never scheduled) or ACTIVITY_RETRY_SCHEDULED (backoff elapsed / force-retry):
        // schedule a fresh attempt on a separate worker, then park this turn.
        int attempt = attemptFromHistory(cursor, seq) + 1;
        scheduleActivityTask(workflowId, seq, activityName, options, activityInterface, method, args, attempt);
        log.info("[{}/{}] activity scheduled seq={} attempt={} — parking workflow for async execution",
                workflowId, activityName, seq, attempt);
        throw new WorkflowParkedException(WorkflowParkedException.Kind.ACTIVITY, seq);
    }

    /**
     * Atomically record ACTIVITY_SCHEDULED and enqueue the {@code activity} task in one transaction,
     * so a crash leaves either both (the worker will run it) or neither (the workflow reschedules) —
     * never a scheduled marker without a task to run it.
     */
    private void scheduleActivityTask(Long workflowId,
                                      int seq,
                                      String activityName,
                                      ActivityOptions options,
                                      Class<?> activityInterface,
                                      Method method,
                                      Object[] args,
                                      int attempt) {
        assertOwned();
        String payloadJson = serializeTaskPayload(
                buildPayload(seq, activityName, options, activityInterface, method, args, attempt), activityName);

        transactionTemplate.executeWithoutResult(s -> {
            Event scheduled = new Event();
            scheduled.setWorkflowId(workflowId);
            scheduled.setEventType(EventType.ACTIVITY_SCHEDULED);
            scheduled.setCommandType(CommandType.ACTIVITY.name());
            scheduled.setSeq(seq);
            scheduled.setActivityName(activityName);
            scheduled.setPayload("{\"attempt\":" + attempt + "}");
            eventRepository.save(scheduled);

            Task t = new Task();
            t.setWorkflowId(workflowId);
            t.setTaskType("activity");
            t.setStatus(TaskStatus.PENDING);
            t.setScheduledAt(Instant.now());
            t.setPayload(payloadJson);
            taskRepository.save(t);
        });
    }

    private ActivityTaskPayload buildPayload(int seq,
                                             String activityName,
                                             ActivityOptions options,
                                             Class<?> activityInterface,
                                             Method method,
                                             Object[] args,
                                             int attempt) {
        List<String> paramTypes = Arrays.stream(method.getParameterTypes()).map(Class::getName).toList();
        Duration timeout = options.getStartToCloseTimeout();
        long timeoutMs = (timeout != null && !timeout.isNegative()) ? timeout.toMillis() : 0L;
        RetryPolicy rp = options.getRetryPolicy() != null ? options.getRetryPolicy() : RetryPolicy.defaultPolicy();
        List<String> noRetryOn = rp.getNoRetryOn().stream().map(Class::getName).toList();
        ActivityTaskPayload.RetryPolicyPayload retry = new ActivityTaskPayload.RetryPolicyPayload(
                rp.getMaxAttempts(),
                rp.getInitialInterval().toMillis(),
                rp.getMaxInterval().toMillis(),
                rp.getBackoffCoefficient(),
                noRetryOn);
        return new ActivityTaskPayload(seq, activityName, activityInterface.getName(), method.getName(),
                paramTypes, args != null ? args : new Object[0], attempt, timeoutMs, retry);
    }

    private String serializeTaskPayload(ActivityTaskPayload payload, String activityName) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize activity task payload for " + activityName, e);
        }
    }

    /** Fence: refuse to write if the workflow's task lease was lost to another node. */
    private void assertOwned() {
        WorkflowContext c = WorkflowContextHolder.current();
        if (c != null) c.getTaskLease().assertOwned();
    }

    /**
     * Highest attempt number recorded for this seq. Each attempt writes its own ACTIVITY_SCHEDULED
     * (and, on failure, ACTIVITY_RETRY_SCHEDULED), so we must take the max across all of them — not
     * the first — or retries would never advance past attempt 2.
     */
    private int attemptFromHistory(HistoryCursor cursor, int seq) {
        int n = 0;
        for (Event e : cursor.eventsForSeq(seq)) {
            if (e.getEventType() == EventType.ACTIVITY_SCHEDULED
                    || e.getEventType() == EventType.ACTIVITY_RETRY_SCHEDULED) {
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
}
