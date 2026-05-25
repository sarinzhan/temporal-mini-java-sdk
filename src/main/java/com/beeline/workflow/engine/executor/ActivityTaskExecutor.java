package com.beeline.workflow.engine.executor;

import com.beeline.workflow.core.config.RetryPolicy;
import com.beeline.workflow.core.exception.ActivityTimeoutException;
import com.beeline.workflow.core.exception.NonRetryableException;
import com.beeline.workflow.core.model.Event;
import com.beeline.workflow.core.model.EventType;
import com.beeline.workflow.core.model.RetryRecord;
import com.beeline.workflow.core.model.Task;
import com.beeline.workflow.core.model.TaskStatus;
import com.beeline.workflow.engine.executor.WorkflowExecutor.Outcome;
import com.beeline.workflow.engine.replay.CommandType;
import com.beeline.workflow.engine.replay.LockLostException;
import com.beeline.workflow.engine.replay.TaskLease;
import com.beeline.workflow.persistence.repository.EventRepository;
import com.beeline.workflow.persistence.repository.RetryRepository;
import com.beeline.workflow.persistence.repository.TaskRepository;
import com.beeline.workflow.registry.ActivityRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.ObjectMapper;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Worker-side runner for {@code activity} tasks. Picked up by {@code WorkerLoopImpl} on a worker
 * thread that is independent of the workflow thread which scheduled the activity. It resolves the
 * activity bean+method from the task payload, deserializes the arguments, runs the activity (with a
 * start-to-close timeout), and records the outcome to the workflow's history:
 *
 * <ul>
 *   <li>success → {@code ACTIVITY_COMPLETED} + enqueue a {@code workflow.wakeup} task;</li>
 *   <li>retryable failure → {@code ACTIVITY_RETRY_SCHEDULED} + a {@link RetryRecord} (the
 *       {@code WakeupScheduler} wakes the workflow at {@code fireAt}, which schedules the next attempt);</li>
 *   <li>terminal failure → {@code ACTIVITY_FAILED} + enqueue a {@code workflow.wakeup} task so the
 *       workflow surfaces the failure promptly.</li>
 * </ul>
 */
public class ActivityTaskExecutor {

    private static final Logger log = LoggerFactory.getLogger(ActivityTaskExecutor.class);

    private final EventRepository eventRepository;
    private final RetryRepository retryRepository;
    private final TaskRepository taskRepository;
    private final ActivityRegistry activityRegistry;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;
    private final ExecutorService invocationPool;

    public ActivityTaskExecutor(EventRepository eventRepository,
                                RetryRepository retryRepository,
                                TaskRepository taskRepository,
                                ActivityRegistry activityRegistry,
                                ObjectMapper objectMapper,
                                PlatformTransactionManager transactionManager) {
        this.eventRepository = eventRepository;
        this.retryRepository = retryRepository;
        this.taskRepository = taskRepository;
        this.activityRegistry = activityRegistry;
        this.objectMapper = objectMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.invocationPool = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "wf-activity-" + System.nanoTime());
            t.setDaemon(true);
            return t;
        });
    }

    public Outcome execute(Task task, TaskLease lease) {
        ActivityTaskPayload payload;
        try {
            payload = objectMapper.readValue(task.getPayload(), ActivityTaskPayload.class);
        } catch (Exception ex) {
            log.error("Activity task {} has an unparseable payload — discarding: {}", task.getId(), ex.toString());
            return Outcome.FAILED;
        }

        Long workflowId = task.getWorkflowId();
        int seq = payload.seq();
        String activityName = payload.activityName();
        int attempt = payload.attempt();
        RetryPolicy policy = reconstructPolicy(payload.retry());

        // Resolve the activity bean+method. A resolution failure is a permanent (non-retryable) error.
        Object bean;
        Method method;
        Object[] args;
        try {
            Class<?> iface = Class.forName(payload.activityInterface());
            bean = activityRegistry.getBean(iface);
            if (bean == null) {
                throw new IllegalStateException("No activity bean registered for " + iface.getName());
            }
            method = resolveMethod(iface, payload.methodName(), payload.paramTypes());
            args = deserializeArgs(payload.args(), method);
        } catch (Exception resolveErr) {
            log.error("[{}/{}] activity resolution failed seq={} — terminal: {}",
                    workflowId, activityName, seq, resolveErr.toString());
            try {
                recordTerminalFailure(lease, workflowId, seq, activityName, attempt, resolveErr);
            } catch (LockLostException lost) {
                return Outcome.LOST;
            }
            return Outcome.COMPLETED;
        }

        try {
            recordStarted(lease, workflowId, seq, activityName, attempt);
            Object result = invokeWithTimeout(bean, method, args, payload.startToCloseTimeoutMillis());
            recordCompleted(lease, workflowId, seq, activityName, attempt, result);
            log.info("[{}/{}] activity COMPLETED seq={} attempt={}", workflowId, activityName, seq, attempt);
            return Outcome.COMPLETED;
        } catch (LockLostException lost) {
            log.warn("[{}/{}] lost activity-task lease seq={} — discarding, another node owns it",
                    workflowId, activityName, seq);
            return Outcome.LOST;
        } catch (Throwable cause) {
            if (lease.isLost()) {
                return Outcome.LOST;
            }
            try {
                failOrRetry(lease, workflowId, task.getId(), seq, activityName, attempt, policy, cause);
            } catch (LockLostException lost) {
                return Outcome.LOST;
            }
            return Outcome.COMPLETED;
        }
    }

    // ── invocation ───────────────────────────────────────────────────────────

    private Object invokeWithTimeout(Object bean, Method method, Object[] args, long timeoutMs) throws Throwable {
        CompletableFuture<Object> future = CompletableFuture.supplyAsync(() -> {
            try {
                return method.invoke(bean, args);
            } catch (InvocationTargetException ite) {
                throw new CompletionException(ite.getCause() != null ? ite.getCause() : ite);
            } catch (IllegalAccessException iae) {
                throw new CompletionException(iae);
            }
        }, invocationPool);
        try {
            if (timeoutMs <= 0) {
                return future.join();
            }
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException te) {
            future.cancel(true);
            throw new ActivityTimeoutException(method.getName(), Duration.ofMillis(timeoutMs));
        } catch (ExecutionException ee) {
            throw ee.getCause() != null ? ee.getCause() : ee;
        } catch (CompletionException ce) {
            throw ce.getCause() != null ? ce.getCause() : ce;
        }
    }

    // ── failure / retry decision ───────────────────────────────────────────────

    private void failOrRetry(TaskLease lease,
                             Long workflowId,
                             Long activityTaskId,
                             int seq,
                             String activityName,
                             int attempt,
                             RetryPolicy policy,
                             Throwable cause) {
        boolean noRetry = cause instanceof NonRetryableException || policy.isNoRetry(cause);
        boolean exhausted = attempt >= policy.getMaxAttempts();
        boolean willRetry = !noRetry && !exhausted;

        if (willRetry) {
            Instant fireAt = Instant.now().plus(policy.nextDelay(attempt));
            lease.assertOwned();
            transactionTemplate.executeWithoutResult(s -> {
                RetryRecord r = new RetryRecord();
                r.setWorkflowId(workflowId);
                r.setActivityName(activityName);
                r.setAttempt(attempt);
                r.setMaxAttempts(policy.getMaxAttempts());
                r.setFireAt(fireAt);
                r.setReason(safeMessage(cause));
                r.setProcessed(false);
                r.setTaskId(activityTaskId);
                retryRepository.save(r);

                Event e = activityEvent(workflowId, EventType.ACTIVITY_RETRY_SCHEDULED, seq, activityName,
                        "{\"attempt\":" + attempt + ",\"fireAt\":\"" + fireAt
                                + "\",\"reason\":\"" + escapeJson(safeMessage(cause)) + "\"}");
                eventRepository.save(e);
            });
            log.warn("[{}/{}] activity FAILED seq={} attempt={} — retrying at {}",
                    workflowId, activityName, seq, attempt, fireAt);
        } else {
            recordTerminalFailure(lease, workflowId, seq, activityName, attempt, cause);
            log.error("[{}/{}] activity FAILED seq={} attempt={} — {}",
                    workflowId, activityName, seq, attempt, noRetry ? "non-retryable" : "attempts exhausted");
        }
    }

    // ── history writes (all fenced by the activity-task lease) ──────────────────

    private void recordStarted(TaskLease lease, Long workflowId, int seq, String activityName, int attempt) {
        lease.assertOwned();
        transactionTemplate.executeWithoutResult(s ->
                eventRepository.save(activityEvent(workflowId, EventType.ACTIVITY_STARTED, seq, activityName,
                        "{\"attempt\":" + attempt + "}")));
    }

    private void recordCompleted(TaskLease lease, Long workflowId, int seq, String activityName,
                                 int attempt, Object result) {
        String payload = buildCompletedPayload(result, attempt);
        lease.assertOwned();
        transactionTemplate.executeWithoutResult(s ->
                eventRepository.save(activityEvent(workflowId, EventType.ACTIVITY_COMPLETED, seq, activityName, payload)));
        enqueueWorkflowWakeup(lease, workflowId, "activity-completed");
    }

    private void recordTerminalFailure(TaskLease lease, Long workflowId, int seq, String activityName,
                                       int attempt, Throwable cause) {
        lease.assertOwned();
        transactionTemplate.executeWithoutResult(s ->
                eventRepository.save(activityEvent(workflowId, EventType.ACTIVITY_FAILED, seq, activityName,
                        "{\"attempt\":" + attempt + ",\"reason\":\"" + escapeJson(safeMessage(cause))
                                + "\",\"terminal\":true}")));
        enqueueWorkflowWakeup(lease, workflowId, "activity-failed");
    }

    /** Enqueue a workflow task so the parked workflow re-runs and observes the recorded activity outcome. */
    private void enqueueWorkflowWakeup(TaskLease lease, Long workflowId, String reason) {
        lease.assertOwned();
        transactionTemplate.executeWithoutResult(s -> {
            Task t = new Task();
            t.setWorkflowId(workflowId);
            t.setTaskType("workflow.wakeup");
            t.setStatus(TaskStatus.PENDING);
            t.setScheduledAt(Instant.now());
            taskRepository.save(t);

            Event queued = new Event();
            queued.setWorkflowId(workflowId);
            queued.setEventType(EventType.WORKFLOW_TASK_QUEUED);
            queued.setPayload("{\"reason\":\"" + reason + "\"}");
            eventRepository.save(queued);
        });
    }

    private Event activityEvent(Long workflowId, EventType type, int seq, String activityName, String payload) {
        Event e = new Event();
        e.setWorkflowId(workflowId);
        e.setEventType(type);
        e.setCommandType(CommandType.ACTIVITY.name());
        e.setSeq(seq);
        e.setActivityName(activityName);
        e.setPayload(payload);
        return e;
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

    // ── resolution helpers ─────────────────────────────────────────────────────

    private Method resolveMethod(Class<?> iface, String name, List<String> paramTypeNames) {
        int n = paramTypeNames == null ? 0 : paramTypeNames.size();
        for (Method m : iface.getMethods()) {
            if (!m.getName().equals(name)) continue;
            Class<?>[] ps = m.getParameterTypes();
            if (ps.length != n) continue;
            boolean match = true;
            for (int i = 0; i < n; i++) {
                if (!ps[i].getName().equals(paramTypeNames.get(i))) { match = false; break; }
            }
            if (match) return m;
        }
        throw new IllegalStateException("Activity method not found: " + iface.getName() + "#" + name
                + " " + paramTypeNames);
    }

    private Object[] deserializeArgs(Object[] raw, Method method) {
        Type[] paramTypes = method.getGenericParameterTypes();
        Object[] out = new Object[paramTypes.length];
        for (int i = 0; i < paramTypes.length; i++) {
            Object rawArg = (raw != null && i < raw.length) ? raw[i] : null;
            if (rawArg == null) {
                out[i] = null;
                continue;
            }
            try {
                JavaType jt = objectMapper.constructType(paramTypes[i]);
                out[i] = objectMapper.readValue(objectMapper.writeValueAsString(rawArg), jt);
            } catch (Exception e) {
                throw new IllegalStateException("Failed to deserialize activity arg #" + i + " for "
                        + method.getName(), e);
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private RetryPolicy reconstructPolicy(ActivityTaskPayload.RetryPolicyPayload p) {
        if (p == null) return RetryPolicy.defaultPolicy();
        RetryPolicy.Builder b = RetryPolicy.newBuilder()
                .setMaxAttempts(p.maxAttempts())
                .setInitialInterval(Duration.ofMillis(p.initialIntervalMillis()))
                .setMaxInterval(Duration.ofMillis(p.maxIntervalMillis()))
                .setBackoffCoefficient(p.backoffCoefficient());
        List<String> noRetryOn = p.noRetryOn() != null ? p.noRetryOn() : new ArrayList<>();
        for (String className : noRetryOn) {
            try {
                Class<?> c = Class.forName(className);
                if (Throwable.class.isAssignableFrom(c)) {
                    b.addNoRetry((Class<? extends Throwable>) c);
                }
            } catch (ClassNotFoundException ignored) {
                // Exception class no longer on classpath — best-effort: treat as retryable.
            }
        }
        return b.build();
    }

    private static String safeMessage(Throwable t) {
        return t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
    }

    private static String escapeJson(String v) {
        if (v == null) return "";
        return v.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
