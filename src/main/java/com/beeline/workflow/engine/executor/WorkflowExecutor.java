package com.beeline.workflow.engine.executor;

import com.beeline.workflow.core.exception.ActivityFailureException;
import com.beeline.workflow.core.model.Event;
import com.beeline.workflow.core.model.EventType;
import com.beeline.workflow.core.model.Task;
import com.beeline.workflow.core.model.WorkflowInstance;
import com.beeline.workflow.core.model.WorkflowStatus;
import com.beeline.workflow.engine.context.WorkflowContextHolder;
import com.beeline.workflow.engine.context.WorkflowContextImpl;
import com.beeline.workflow.core.model.UpdateRequest;
import com.beeline.workflow.engine.replay.CommandType;
import com.beeline.workflow.engine.replay.EventSink;
import com.beeline.workflow.engine.replay.EventSinkImpl;
import com.beeline.workflow.engine.replay.HistoryCursor;
import com.beeline.workflow.engine.replay.LockLostException;
import com.beeline.workflow.engine.replay.NonDeterminismException;
import com.beeline.workflow.engine.replay.TaskLease;
import com.beeline.workflow.engine.replay.WakeupRegistrar;
import com.beeline.workflow.engine.replay.WakeupRegistrarImpl;
import com.beeline.workflow.engine.replay.WorkflowParkedException;
import com.beeline.workflow.engine.update.UpdateRegistry;
import com.beeline.workflow.persistence.repository.PendingAwaitRepository;
import com.beeline.workflow.persistence.repository.PendingTimerRepository;
import com.beeline.workflow.persistence.repository.EventRepository;
import com.beeline.workflow.persistence.repository.UpdateRequestRepository;
import com.beeline.workflow.persistence.repository.WorkflowRepository;
import com.beeline.workflow.registry.ActivityRegistry;
import com.beeline.workflow.registry.WorkflowRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.ObjectMapper;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

public class WorkflowExecutor {

    private static final Logger log = LoggerFactory.getLogger(WorkflowExecutor.class);

    private final WorkflowRegistry workflowRegistry;
    private final ActivityRegistry activityRegistry;
    private final ActivityExecutor activityExecutor;
    private final WorkflowRepository workflowRepository;
    private final EventRepository eventRepository;
    private final PendingTimerRepository pendingTimerRepository;
    private final PendingAwaitRepository pendingAwaitRepository;
    private final UpdateRequestRepository updateRequestRepository;
    private final UpdateRegistry updateRegistry;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    public WorkflowExecutor(WorkflowRegistry workflowRegistry,
                            ActivityRegistry activityRegistry,
                            ActivityExecutor activityExecutor,
                            WorkflowRepository workflowRepository,
                            EventRepository eventRepository,
                            PendingTimerRepository pendingTimerRepository,
                            PendingAwaitRepository pendingAwaitRepository,
                            UpdateRequestRepository updateRequestRepository,
                            UpdateRegistry updateRegistry,
                            ObjectMapper objectMapper,
                            PlatformTransactionManager transactionManager) {
        this.workflowRegistry = workflowRegistry;
        this.activityRegistry = activityRegistry;
        this.activityExecutor = activityExecutor;
        this.workflowRepository = workflowRepository;
        this.eventRepository = eventRepository;
        this.pendingTimerRepository = pendingTimerRepository;
        this.pendingAwaitRepository = pendingAwaitRepository;
        this.updateRequestRepository = updateRequestRepository;
        this.updateRegistry = updateRegistry;
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

        Method entryMethod;
        try {
            entryMethod = findEntryMethod(bean.getClass());
        } catch (RuntimeException ex) {
            markFailed(wf, ex.getMessage());
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
        WakeupRegistrar wakeup = new WakeupRegistrarImpl(
                wf.getId(), pendingTimerRepository, pendingAwaitRepository, transactionTemplate);

        markRunning(wf);
        saveEvent(wf, EventType.WORKFLOW_TASK_STARTED, null);

        WorkflowContextImpl ctx = new WorkflowContextImpl(
                wf.getId(), task.getId(), activityExecutor, activityRegistry, cursor, sink, wakeup, lease);
        WorkflowContextHolder.set(ctx);
        // Singleton workflow bean has shared mutable fields. Synchronize the whole turn (entry + updates)
        // so that concurrent workflow instances of the same type don't race on field state.
        synchronized (bean) {
            try {
                Outcome entryOutcome = runEntry(wf, bean, entryMethod, callArgs);
                // After the entry method parked/completed, dispatch any pending updates for this workflow.
                processPendingUpdates(wf, bean);
                return entryOutcome;
            } catch (LockLostException lost) {
                // Lease expired and another node reclaimed the task mid-turn. Discard this turn:
                // every write was fenced, so nothing was persisted. The new owner takes over.
                log.warn("[{}] lock lost during turn — discarding writes, another node owns the task", wf.getId());
                return Outcome.LOST;
            } catch (Exception ex) {
                markFailed(wf, safeMessage(ex));
                saveEvent(wf, EventType.WORKFLOW_FAILED, safeMessage(ex));
                saveEvent(wf, EventType.WORKFLOW_TASK_COMPLETED, null);
                return Outcome.FAILED;
            } finally {
                WorkflowContextHolder.clear();
            }
        }
    }

    /** Fence: throw {@link LockLostException} if we no longer own the task driving this turn. */
    private void assertOwned() {
        var c = WorkflowContextHolder.current();
        if (c != null) c.getTaskLease().assertOwned();
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
                log.info("[{}] workflow parked: {} seq={}", wf.getId(), parked.getKind(), parked.getSeq());
                saveEvent(wf, EventType.WORKFLOW_TASK_COMPLETED, null);
                return Outcome.PARKED;
            }
            if (cause instanceof ActivityFailureException afe) {
                log.info("[{}] workflow paused — activity {} failed, retry scheduled",
                        wf.getId(), afe.getActivityName());
                saveEvent(wf, EventType.WORKFLOW_TASK_COMPLETED, null);
                return Outcome.RETRYING;
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

    private void processPendingUpdates(WorkflowInstance wf, Object bean) {
        List<UpdateRequest> pendings =
                updateRequestRepository.findByWorkflowIdAndStatusOrderByCreatedAtAsc(wf.getId(), "PENDING");
        for (UpdateRequest req : pendings) {
            Method updateMethod = workflowRegistry.getUpdateMethod(wf.getWorkflowType(), req.getMethodName());
            if (updateMethod == null) {
                String err = "no @UpdateMethod named '" + req.getMethodName() + "' on " + wf.getWorkflowType();
                completeUpdateFailure(req, err);
                continue;
            }
            try {
                assertOwned();
                Object[] args = buildUpdateArgs(updateMethod, req.getArgsPayload());
                Object result = updateMethod.invoke(bean, args);
                completeUpdateSuccess(req, result);
            } catch (LockLostException lost) {
                throw lost;  // stop dispatching updates — we lost the lock
            } catch (InvocationTargetException ite) {
                Throwable cause = ite.getCause() != null ? ite.getCause() : ite;
                if (cause instanceof LockLostException lost) throw lost;
                completeUpdateFailure(req, safeMessage(cause));
            } catch (Exception ex) {
                completeUpdateFailure(req, safeMessage(ex));
            }
        }
    }

    private void completeUpdateSuccess(UpdateRequest req, Object result) {
        assertOwned();
        transactionTemplate.executeWithoutResult(s -> {
            req.setStatus("COMPLETED");
            req.setResult(serialize(result));
            req.setCompletedAt(Instant.now());
            updateRequestRepository.save(req);

            Event done = new Event();
            done.setWorkflowId(req.getWorkflowId());
            done.setEventType(EventType.UPDATE_COMPLETED);
            done.setCommandType(CommandType.UPDATE.name());
            done.setActivityName(req.getMethodName());
            done.setPayload("{\"updateId\":\"" + req.getUpdateId() + "\",\"result\":"
                    + (req.getResult() != null ? req.getResult() : "null") + "}");
            eventRepository.save(done);
        });
        updateRegistry.completeSuccess(req.getUpdateId(), result);
        log.info("Update {}::{} completed for workflow {}",
                req.getMethodName(), req.getUpdateId(), req.getWorkflowId());
    }

    private void completeUpdateFailure(UpdateRequest req, String error) {
        assertOwned();
        transactionTemplate.executeWithoutResult(s -> {
            req.setStatus("FAILED");
            req.setError(error);
            req.setCompletedAt(Instant.now());
            updateRequestRepository.save(req);

            Event done = new Event();
            done.setWorkflowId(req.getWorkflowId());
            done.setEventType(EventType.UPDATE_COMPLETED);
            done.setCommandType(CommandType.UPDATE.name());
            done.setActivityName(req.getMethodName());
            done.setPayload("{\"updateId\":\"" + req.getUpdateId()
                    + "\",\"error\":\"" + escapeJson(error) + "\"}");
            eventRepository.save(done);
        });
        updateRegistry.completeFailure(req.getUpdateId(), error);
        log.warn("Update {}::{} FAILED for workflow {}: {}",
                req.getMethodName(), req.getUpdateId(), req.getWorkflowId(), error);
    }

    private Object[] buildUpdateArgs(Method updateMethod, String argsPayload) {
        int n = updateMethod.getParameterCount();
        if (n == 0) return new Object[0];
        try {
            JavaType arrType = objectMapper.getTypeFactory().constructArrayType(Object.class);
            Object[] raw = argsPayload != null ? objectMapper.readValue(argsPayload, Object[].class) : new Object[0];
            if (raw.length != n) {
                throw new IllegalArgumentException(
                        "update " + updateMethod.getName() + " expects " + n + " args, got " + raw.length);
            }
            Type[] paramTypes = updateMethod.getGenericParameterTypes();
            Object[] coerced = new Object[n];
            for (int i = 0; i < n; i++) {
                JavaType jt = objectMapper.constructType(paramTypes[i]);
                String json = objectMapper.writeValueAsString(raw[i]);
                coerced[i] = objectMapper.readValue(json, jt);
            }
            return coerced;
        } catch (RuntimeException re) {
            throw re;
        } catch (Exception e) {
            throw new RuntimeException("Failed to deserialize update args", e);
        }
    }

    private static String escapeJson(String v) {
        if (v == null) return "";
        return v.replace("\\", "\\\\").replace("\"", "\\\"");
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

    private Method findEntryMethod(Class<?> beanClass) {
        List<Method> candidates = Arrays.stream(beanClass.getDeclaredMethods())
                .filter(m -> Modifier.isPublic(m.getModifiers()))
                .filter(m -> !Modifier.isStatic(m.getModifiers()))
                .filter(m -> !m.isSynthetic() && !m.isBridge())
                .filter(m -> !m.isAnnotationPresent(com.beeline.workflow.core.annotation.QueryMethod.class))
                .filter(m -> !m.isAnnotationPresent(com.beeline.workflow.core.annotation.UpdateMethod.class))
                .toList();
        if (candidates.size() == 1) return candidates.get(0);
        Optional<Method> namedRun = candidates.stream()
                .filter(m -> m.getName().equals("run"))
                .findFirst();
        if (namedRun.isPresent()) return namedRun.get();
        throw new IllegalStateException(
                "Cannot determine workflow entry method on " + beanClass.getName() +
                " — expected exactly one public method, or one named 'run'.");
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
        RETRYING,
        PARKED,
        FAILED,
        UNKNOWN,
        /** Lease was lost mid-turn; writes were discarded and the new owner takes over. */
        LOST
    }
}
