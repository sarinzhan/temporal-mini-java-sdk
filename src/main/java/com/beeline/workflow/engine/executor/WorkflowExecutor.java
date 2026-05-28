package com.beeline.workflow.engine.executor;

import com.beeline.workflow.core.exception.ActivityFailureException;
import com.beeline.workflow.core.model.Event;
import com.beeline.workflow.core.model.EventType;
import com.beeline.workflow.core.model.Task;
import com.beeline.workflow.core.model.WorkflowInstance;
import com.beeline.workflow.core.model.WorkflowStatus;
import com.beeline.workflow.engine.context.WorkflowContextHolder;
import com.beeline.workflow.engine.context.WorkflowContextImpl;
import com.beeline.workflow.engine.replay.EventSink;
import com.beeline.workflow.engine.replay.EventSinkImpl;
import com.beeline.workflow.engine.replay.HistoryCursor;
import com.beeline.workflow.engine.replay.LockLostException;
import com.beeline.workflow.engine.replay.NonDeterminismException;
import com.beeline.workflow.engine.replay.TaskLease;
import com.beeline.workflow.engine.replay.WorkflowParkedException;
import com.beeline.workflow.persistence.repository.EventRepository;
import com.beeline.workflow.persistence.repository.WorkflowRepository;
import com.beeline.workflow.registry.WorkflowRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.ObjectMapper;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.time.Instant;
import java.util.List;

/**
 * Drives one workflow decision turn. The entry method runs inline to completion in a single thread:
 * activities execute inline (results cached in history), and the only suspension is an activity
 * retry that parks the turn until the {@code WakeupScheduler} re-enqueues it. On process/node crash
 * mid-turn, the lease expires and another node reclaims the task and replays from the top, skipping
 * already-completed activities.
 */
public class WorkflowExecutor {

    private static final Logger log = LoggerFactory.getLogger(WorkflowExecutor.class);

    private final WorkflowRegistry workflowRegistry;
    private final ActivityExecutor activityExecutor;
    private final WorkflowRepository workflowRepository;
    private final EventRepository eventRepository;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    public WorkflowExecutor(WorkflowRegistry workflowRegistry,
                            ActivityExecutor activityExecutor,
                            WorkflowRepository workflowRepository,
                            EventRepository eventRepository,
                            ObjectMapper objectMapper,
                            PlatformTransactionManager transactionManager) {
        this.workflowRegistry = workflowRegistry;
        this.activityExecutor = activityExecutor;
        this.workflowRepository = workflowRepository;
        this.eventRepository = eventRepository;
        this.objectMapper = objectMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public Outcome execute(Task task, TaskLease lease) {
        WorkflowInstance wf = workflowRepository.findById(task.getWorkflowId()).orElse(null);
        if (wf == null) {
            log.warn("Task {} references missing workflow {} — discarding", task.getId(), task.getWorkflowId());
            return Outcome.UNKNOWN;
        }
        Object bean = workflowRegistry.getBean(wf.getWorkflowType());
        if (bean == null) {
            markFailed(wf, "Unknown workflow type: " + wf.getWorkflowType());
            return Outcome.FAILED;
        }

        Method entryMethod = workflowRegistry.getEntryMethod(wf.getWorkflowType());
        if (entryMethod == null) {
            markFailed(wf, "No @WorkflowMethod registered for type: " + wf.getWorkflowType());
            return Outcome.FAILED;
        }

        Object[] callArgs;
        try {
            callArgs = buildCallArgs(entryMethod, wf.getInput());
        } catch (Exception ex) {
            markFailed(wf, "Failed to deserialize workflow input: " + ex.getMessage());
            return Outcome.FAILED;
        }

        // Load history *before* writing TASK_STARTED so the cursor only sees prior turns.
        List<Event> history = eventRepository.findByWorkflowIdOrderByIdAsc(wf.getId());
        HistoryCursor cursor = new HistoryCursor(wf.getId(), history, objectMapper);
        EventSink sink = new EventSinkImpl(wf.getId(), eventRepository, transactionTemplate, lease);

        markRunning(wf);
        saveEvent(wf, EventType.WORKFLOW_TASK_STARTED, null);

        WorkflowContextImpl ctx = new WorkflowContextImpl(
                wf.getId(), task.getId(), activityExecutor, cursor, sink, lease);
        WorkflowContextHolder.set(ctx);
        // The singleton workflow bean has shared mutable fields. Serialize turns of the same bean so
        // concurrent instances of the same type don't race on field state.
        synchronized (bean) {
            try {
                return runEntry(wf, bean, entryMethod, callArgs);
            } catch (LockLostException lost) {
                log.warn("[{}] lock lost during turn — discarding writes, another node owns the task", wf.getId());
                return Outcome.LOST;
            } finally {
                WorkflowContextHolder.clear();
            }
        }
    }

    private Outcome runEntry(WorkflowInstance wf, Object bean, Method entryMethod, Object[] callArgs) {
        try {
            Object result = entryMethod.invoke(bean, callArgs);
            markCompleted(wf, result);
            saveEvent(wf, EventType.WORKFLOW_COMPLETED, serialize(result));
            saveEvent(wf, EventType.WORKFLOW_TASK_COMPLETED, null);
            return Outcome.COMPLETED;
        } catch (InvocationTargetException ite) {
            Throwable cause = ite.getCause() != null ? ite.getCause() : ite;
            if (cause instanceof LockLostException lost) {
                throw lost;  // handled in execute(): discard the turn, don't mark failed
            }
            if (cause instanceof WorkflowParkedException parked) {
                log.info("[{}] workflow parked: activity retry seq={}", wf.getId(), parked.getSeq());
                saveEvent(wf, EventType.WORKFLOW_TASK_COMPLETED, null);
                return Outcome.PARKED;
            }
            if (cause instanceof ActivityFailureException afe) {
                log.warn("[{}] workflow failed — activity {} terminally failed", wf.getId(), afe.getActivityName());
                markFailed(wf, safeMessage(afe));
                saveEvent(wf, EventType.WORKFLOW_FAILED, safeMessage(afe));
                saveEvent(wf, EventType.WORKFLOW_TASK_COMPLETED, null);
                return Outcome.FAILED;
            }
            if (cause instanceof NonDeterminismException nde) {
                log.error("[{}] workflow non-determinism detected: {}", wf.getId(), nde.getMessage());
                markFailed(wf, "Non-determinism: " + nde.getMessage());
                saveEvent(wf, EventType.WORKFLOW_FAILED, nde.getMessage());
                saveEvent(wf, EventType.WORKFLOW_TASK_COMPLETED, null);
                return Outcome.FAILED;
            }
            markFailed(wf, safeMessage(cause));
            saveEvent(wf, EventType.WORKFLOW_FAILED, safeMessage(cause));
            saveEvent(wf, EventType.WORKFLOW_TASK_COMPLETED, null);
            return Outcome.FAILED;
        } catch (LockLostException lost) {
            throw lost;  // a fenced write (markCompleted/saveEvent) detected the lost lock
        } catch (Exception ex) {
            markFailed(wf, safeMessage(ex));
            saveEvent(wf, EventType.WORKFLOW_FAILED, safeMessage(ex));
            saveEvent(wf, EventType.WORKFLOW_TASK_COMPLETED, null);
            return Outcome.FAILED;
        }
    }

    private void assertOwned() {
        var c = WorkflowContextHolder.current();
        if (c != null) c.getTaskLease().assertOwned();
    }

    private void markRunning(WorkflowInstance wf) {
        assertOwned();
        transactionTemplate.executeWithoutResult(s -> {
            wf.setStatus(WorkflowStatus.RUNNING);
            wf.setUpdatedAt(Instant.now());
            workflowRepository.save(wf);
        });
    }

    private void markCompleted(WorkflowInstance wf, Object result) {
        assertOwned();
        transactionTemplate.executeWithoutResult(s -> {
            wf.setStatus(WorkflowStatus.COMPLETED);
            wf.setResult(serialize(result));
            wf.setError(null);
            wf.setCompletedAt(Instant.now());
            wf.setUpdatedAt(Instant.now());
            workflowRepository.save(wf);
        });
    }

    private void markFailed(WorkflowInstance wf, String error) {
        assertOwned();
        transactionTemplate.executeWithoutResult(s -> {
            wf.setStatus(WorkflowStatus.FAILED);
            wf.setError(error);
            wf.setUpdatedAt(Instant.now());
            workflowRepository.save(wf);
        });
    }

    private void saveEvent(WorkflowInstance wf, EventType type, String payload) {
        assertOwned();
        transactionTemplate.executeWithoutResult(s -> {
            Event e = new Event();
            e.setWorkflowId(wf.getId());
            e.setEventType(type);
            e.setPayload(payload);
            eventRepository.save(e);
        });
    }

    private Object[] buildCallArgs(Method entryMethod, String inputJson) throws Exception {
        int count = entryMethod.getParameterCount();
        if (count == 0) return new Object[0];
        if (count == 1) {
            Type paramType = entryMethod.getGenericParameterTypes()[0];
            Object value = (inputJson == null) ? null : deserialize(inputJson, paramType);
            return new Object[]{value};
        }
        throw new IllegalStateException(
                "Workflow entry method must take 0 or 1 parameters: " + entryMethod);
    }

    private String serialize(Object value) {
        if (value == null) return null;
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize workflow result", e);
        }
    }

    private Object deserialize(String json, Type type) {
        if (json == null) return null;
        try {
            JavaType jt = objectMapper.constructType(type);
            return objectMapper.readValue(json, jt);
        } catch (Exception e) {
            throw new RuntimeException("Failed to deserialize workflow input", e);
        }
    }

    private static String safeMessage(Throwable t) {
        return t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
    }

    public enum Outcome {
        COMPLETED,
        /** Parked waiting out an activity retry backoff; the WakeupScheduler will re-enqueue. */
        PARKED,
        FAILED,
        UNKNOWN,
        /** Lease was lost mid-turn; writes were discarded and the new owner takes over. */
        LOST
    }
}
