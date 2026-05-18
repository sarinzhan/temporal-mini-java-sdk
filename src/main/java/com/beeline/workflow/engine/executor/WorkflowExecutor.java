package com.beeline.workflow.engine.executor;

import com.beeline.workflow.core.exception.ActivityFailureException;
import com.beeline.workflow.core.model.Event;
import com.beeline.workflow.core.model.EventType;
import com.beeline.workflow.core.model.Task;
import com.beeline.workflow.core.model.WorkflowInstance;
import com.beeline.workflow.core.model.WorkflowStatus;
import com.beeline.workflow.engine.context.WorkflowContextHolder;
import com.beeline.workflow.engine.context.WorkflowContextImpl;
import com.beeline.workflow.persistence.repository.EventRepository;
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
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    public WorkflowExecutor(WorkflowRegistry workflowRegistry,
                            ActivityRegistry activityRegistry,
                            ActivityExecutor activityExecutor,
                            WorkflowRepository workflowRepository,
                            EventRepository eventRepository,
                            ObjectMapper objectMapper,
                            PlatformTransactionManager transactionManager) {
        this.workflowRegistry = workflowRegistry;
        this.activityRegistry = activityRegistry;
        this.activityExecutor = activityExecutor;
        this.workflowRepository = workflowRepository;
        this.eventRepository = eventRepository;
        this.objectMapper = objectMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public Outcome execute(Task task) {
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

        markRunning(wf);
        saveEvent(wf, EventType.WORKFLOW_STARTED, null);

        WorkflowContextImpl ctx = new WorkflowContextImpl(
                wf.getId(), task.getId(), activityExecutor, activityRegistry);
        WorkflowContextHolder.set(ctx);
        try {
            Object result = entryMethod.invoke(bean, callArgs);
            markCompleted(wf, result);
            saveEvent(wf, EventType.WORKFLOW_COMPLETED, serialize(result));
            return Outcome.COMPLETED;
        } catch (InvocationTargetException ite) {
            Throwable cause = ite.getCause() != null ? ite.getCause() : ite;
            if (cause instanceof ActivityFailureException afe) {
                log.info("[{}] workflow paused — activity {} failed, retry scheduled",
                        wf.getId(), afe.getActivityName());
                return Outcome.RETRYING;
            }
            markFailed(wf, safeMessage(cause));
            saveEvent(wf, EventType.WORKFLOW_FAILED, safeMessage(cause));
            return Outcome.FAILED;
        } catch (Exception ex) {
            markFailed(wf, safeMessage(ex));
            saveEvent(wf, EventType.WORKFLOW_FAILED, safeMessage(ex));
            return Outcome.FAILED;
        } finally {
            WorkflowContextHolder.clear();
        }
    }

    private void markRunning(WorkflowInstance wf) {
        transactionTemplate.executeWithoutResult(s -> {
            wf.setStatus(WorkflowStatus.RUNNING);
            wf.setUpdatedAt(Instant.now());
            workflowRepository.save(wf);
        });
    }

    private void markCompleted(WorkflowInstance wf, Object result) {
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
        transactionTemplate.executeWithoutResult(s -> {
            wf.setStatus(WorkflowStatus.FAILED);
            wf.setError(error);
            wf.setUpdatedAt(Instant.now());
            workflowRepository.save(wf);
        });
    }

    private void saveEvent(WorkflowInstance wf, EventType type, String data) {
        transactionTemplate.executeWithoutResult(s -> {
            Event e = new Event();
            e.setWorkflowId(wf.getId());
            e.setEventType(type);
            e.setData(data);
            eventRepository.save(e);
        });
    }

    private Method findEntryMethod(Class<?> beanClass) {
        List<Method> candidates = Arrays.stream(beanClass.getDeclaredMethods())
                .filter(m -> Modifier.isPublic(m.getModifiers()))
                .filter(m -> !Modifier.isStatic(m.getModifiers()))
                .filter(m -> !m.isSynthetic() && !m.isBridge())
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
        FAILED,
        UNKNOWN
    }
}
