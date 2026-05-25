package com.beeline.workflow.engine.query;

import com.beeline.workflow.core.model.Event;
import com.beeline.workflow.core.model.EventType;
import com.beeline.workflow.core.model.WorkflowInstance;
import com.beeline.workflow.engine.context.WorkflowContextHolder;
import com.beeline.workflow.engine.context.WorkflowContextImpl;
import com.beeline.workflow.engine.executor.ActivityExecutor;
import com.beeline.workflow.engine.replay.EventSink;
import com.beeline.workflow.engine.replay.HistoryCursor;
import com.beeline.workflow.engine.replay.QueryReplayBlockedException;
import com.beeline.workflow.engine.replay.WakeupRegistrar;
import com.beeline.workflow.persistence.repository.EventRepository;
import com.beeline.workflow.persistence.repository.WorkflowRepository;
import com.beeline.workflow.registry.ActivityRegistry;
import com.beeline.workflow.registry.WorkflowRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;
import org.springframework.context.ApplicationContext;
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

/**
 * Runs a workflow's {@code @QueryMethod} synchronously over the recorded history.
 * Creates a fresh wired instance of the workflow class for each query (no shared
 * mutable state across queries), drives the entry method in query mode until it
 * hits {@link QueryReplayBlockedException} (or returns), then invokes the query
 * method on the same instance to extract the answer.
 */
public class WorkflowQueryRuntime {

    private static final Logger log = LoggerFactory.getLogger(WorkflowQueryRuntime.class);

    private final ApplicationContext applicationContext;
    private final WorkflowRepository workflowRepository;
    private final EventRepository eventRepository;
    private final WorkflowRegistry workflowRegistry;
    private final ActivityRegistry activityRegistry;
    private final ActivityExecutor activityExecutor;
    private final ObjectMapper objectMapper;

    public WorkflowQueryRuntime(ApplicationContext applicationContext,
                                WorkflowRepository workflowRepository,
                                EventRepository eventRepository,
                                WorkflowRegistry workflowRegistry,
                                ActivityRegistry activityRegistry,
                                ActivityExecutor activityExecutor,
                                ObjectMapper objectMapper) {
        this.applicationContext = applicationContext;
        this.workflowRepository = workflowRepository;
        this.eventRepository = eventRepository;
        this.workflowRegistry = workflowRegistry;
        this.activityRegistry = activityRegistry;
        this.activityExecutor = activityExecutor;
        this.objectMapper = objectMapper;
    }

    public Object runQuery(Long workflowId, String queryName, List<Object> args) {
        WorkflowInstance wf = workflowRepository.findById(workflowId)
                .orElseThrow(() -> new IllegalArgumentException("workflow not found: " + workflowId));
        Class<?> beanClass = workflowRegistry.getBeanClass(wf.getWorkflowType());
        if (beanClass == null) {
            throw new IllegalStateException("unknown workflow type: " + wf.getWorkflowType());
        }
        Method queryMethod = workflowRegistry.getQueryMethod(wf.getWorkflowType(), queryName);
        if (queryMethod == null) {
            throw new IllegalArgumentException(
                    "no @QueryMethod named '" + queryName + "' on " + wf.getWorkflowType());
        }

        // Fresh wired instance — isolates state across queries and from the live worker.
        Object instance = applicationContext.getAutowireCapableBeanFactory().createBean(beanClass);

        Method entry = findEntryMethod(beanClass);
        Object[] entryArgs = buildEntryArgs(entry, wf.getInput());

        List<Event> history = eventRepository.findByWorkflowIdOrderByIdAsc(workflowId);
        HistoryCursor cursor = new HistoryCursor(workflowId, history, objectMapper);
        cursor.setQueryMode(true);

        WorkflowContextImpl ctx = new WorkflowContextImpl(
                workflowId, null, activityExecutor, activityRegistry, cursor,
                noopSink(), noopRegistrar(), com.beeline.workflow.engine.replay.TaskLease.ALWAYS_OWNED);
        WorkflowContextHolder.set(ctx);
        try {
            // Reconstruct signal-driven state so queries observe fields set by @SignalMethod handlers,
            // exactly as the live turn does before replaying the entry method.
            deliverSignals(wf.getWorkflowType(), instance, history);
            try {
                entry.invoke(instance, entryArgs);
            } catch (InvocationTargetException ite) {
                Throwable cause = ite.getCause() != null ? ite.getCause() : ite;
                if (!(cause instanceof QueryReplayBlockedException)) {
                    // The workflow may have legitimately finished — that's fine. Any other exception
                    // is "workflow already failed" — also fine, we still call the query.
                    log.debug("query replay exited with {}: {}", cause.getClass().getSimpleName(), cause.getMessage());
                }
            }

            Object[] queryArgs = buildQueryArgs(queryMethod, args);
            try {
                return queryMethod.invoke(instance, queryArgs);
            } catch (InvocationTargetException ite) {
                Throwable cause = ite.getCause() != null ? ite.getCause() : ite;
                if (cause instanceof RuntimeException re) throw re;
                throw new RuntimeException(cause);
            }
        } catch (Exception e) {
            if (e instanceof RuntimeException re) throw re;
            throw new RuntimeException(e);
        } finally {
            WorkflowContextHolder.clear();
        }
    }

    /** Mirror of WorkflowExecutor.deliverSignals: replay SIGNAL_RECEIVED events into @SignalMethod handlers. */
    private void deliverSignals(String workflowType, Object instance, List<Event> history) {
        for (Event e : history) {
            if (e.getEventType() != EventType.SIGNAL_RECEIVED) continue;
            Method handler = workflowRegistry.getSignalMethod(workflowType, e.getActivityName());
            if (handler == null) continue;
            try {
                handler.invoke(instance, buildSignalArgs(handler, e.getPayload()));
            } catch (ReflectiveOperationException roe) {
                // A failing signal handler shouldn't break a query; log and continue with partial state.
                log.debug("signal handler {} threw during query replay: {}", e.getActivityName(), roe.getMessage());
            }
        }
    }

    private Object[] buildSignalArgs(Method handler, String payload) {
        if (handler.getParameterCount() == 0) return new Object[0];
        Type paramType = handler.getGenericParameterTypes()[0];
        if (paramType == String.class) return new Object[]{payload};
        if (payload == null) return new Object[]{null};
        try {
            JavaType jt = objectMapper.constructType(paramType);
            return new Object[]{objectMapper.readValue(payload, jt)};
        } catch (Exception ex) {
            throw new RuntimeException("Failed to deserialize signal payload for handler "
                    + handler.getName(), ex);
        }
    }

    private Method findEntryMethod(Class<?> beanClass) {
        List<Method> candidates = Arrays.stream(beanClass.getDeclaredMethods())
                .filter(m -> Modifier.isPublic(m.getModifiers()))
                .filter(m -> !Modifier.isStatic(m.getModifiers()))
                .filter(m -> !m.isSynthetic() && !m.isBridge())
                .filter(m -> !m.isAnnotationPresent(com.beeline.workflow.core.annotation.QueryMethod.class))
                .filter(m -> !m.isAnnotationPresent(com.beeline.workflow.core.annotation.UpdateMethod.class))
                .filter(m -> !m.isAnnotationPresent(com.beeline.workflow.core.annotation.SignalMethod.class))
                .toList();
        if (candidates.size() == 1) return candidates.get(0);
        Optional<Method> namedRun = candidates.stream().filter(m -> m.getName().equals("run")).findFirst();
        return namedRun.orElseThrow(() -> new IllegalStateException(
                "Cannot determine workflow entry method on " + beanClass.getName()));
    }

    private Object[] buildEntryArgs(Method entry, String inputJson) {
        int count = entry.getParameterCount();
        if (count == 0) return new Object[0];
        if (count == 1) {
            Type paramType = entry.getGenericParameterTypes()[0];
            if (inputJson == null) return new Object[]{null};
            try {
                JavaType jt = objectMapper.constructType(paramType);
                return new Object[]{objectMapper.readValue(inputJson, jt)};
            } catch (Exception e) {
                throw new RuntimeException("Failed to deserialize workflow input for query", e);
            }
        }
        throw new IllegalStateException("Workflow entry must have 0 or 1 parameters: " + entry);
    }

    private Object[] buildQueryArgs(Method queryMethod, List<Object> rawArgs) {
        int n = queryMethod.getParameterCount();
        if (n == 0) return new Object[0];
        if (rawArgs == null || rawArgs.size() != n) {
            throw new IllegalArgumentException(
                    "query method " + queryMethod.getName() + " expects " + n + " args, got "
                            + (rawArgs == null ? 0 : rawArgs.size()));
        }
        Type[] paramTypes = queryMethod.getGenericParameterTypes();
        Object[] coerced = new Object[n];
        for (int i = 0; i < n; i++) {
            JavaType jt = objectMapper.constructType(paramTypes[i]);
            try {
                String json = objectMapper.writeValueAsString(rawArgs.get(i));
                coerced[i] = objectMapper.readValue(json, jt);
            } catch (Exception e) {
                throw new RuntimeException("Failed to coerce query arg #" + i, e);
            }
        }
        return coerced;
    }

    private EventSink noopSink() {
        return (type, cmdType, seq, name, payload) -> {
            throw new IllegalStateException(
                    "EventSink invoked during query replay — should not happen (queryMode guards must short-circuit first)");
        };
    }

    private WakeupRegistrar noopRegistrar() {
        return new WakeupRegistrar() {
            @Override public void registerTimer(int seq, Instant fireAt) {}
            @Override public void registerAwait(int seq, Instant deadline) {}
            @Override public void deleteAwait(int seq) {}
        };
    }
}
