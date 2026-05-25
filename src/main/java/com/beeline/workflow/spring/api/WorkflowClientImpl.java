package com.beeline.workflow.spring.api;

import com.beeline.workflow.core.annotation.WorkflowMethod;
import com.beeline.workflow.core.config.WorkflowOptions;
import com.beeline.workflow.core.model.Event;
import com.beeline.workflow.core.model.EventType;
import com.beeline.workflow.core.model.Task;
import com.beeline.workflow.core.model.TaskStatus;
import com.beeline.workflow.core.model.WorkflowInstance;
import com.beeline.workflow.core.model.WorkflowStatus;
import com.beeline.workflow.engine.stub.WorkflowStubCallCapture;
import com.beeline.workflow.engine.stub.WorkflowStubFactory;
import com.beeline.workflow.engine.stub.WorkflowStubInvocationHandler;
import com.beeline.workflow.persistence.repository.EventRepository;
import com.beeline.workflow.persistence.repository.TaskRepository;
import com.beeline.workflow.persistence.repository.WorkflowRepository;
import com.beeline.workflow.registry.WorkflowRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.Instant;

public class WorkflowClientImpl implements WorkflowClient, WorkflowStubFactory.WorkflowStarter {

    private static final Logger log = LoggerFactory.getLogger(WorkflowClientImpl.class);

    private final WorkflowRepository workflowRepository;
    private final TaskRepository taskRepository;
    private final EventRepository eventRepository;
    private final WorkflowRegistry workflowRegistry;
    private final ObjectMapper objectMapper;

    public WorkflowClientImpl(WorkflowRepository workflowRepository,
                              TaskRepository taskRepository,
                              EventRepository eventRepository,
                              WorkflowRegistry workflowRegistry,
                              ObjectMapper objectMapper) {
        this.workflowRepository = workflowRepository;
        this.taskRepository = taskRepository;
        this.eventRepository = eventRepository;
        this.workflowRegistry = workflowRegistry;
        this.objectMapper = objectMapper;
    }

    @Override
    public <T> T newWorkflowStub(Class<T> iface, WorkflowOptions opts) {
        return WorkflowStubFactory.create(iface, opts, null);
    }

    @Override
    public <T> T newWorkflowStub(Class<T> iface, Long instanceId) {
        return WorkflowStubFactory.create(iface, WorkflowOptions.defaultOptions(), instanceId);
    }

    @Override
    public <A, R> WorkflowHandle<R> start(WfFunc1<A, R> fn, A arg) {
        return startCapture(() -> fn.apply(arg));
    }

    @Override
    public <R> WorkflowHandle<R> start(WfFunc0<R> fn) {
        return startCapture(fn::apply);
    }

    @Override
    public <A> WorkflowHandle<Void> start(WfProc1<A> fn, A arg) {
        return startCapture(() -> { fn.apply(arg); return null; });
    }

    @Override
    public WorkflowHandle<Void> start(WfProc0 fn) {
        return startCapture(() -> { fn.apply(); return null; });
    }

    /** Bridge from {@code WorkflowStubFactory.WorkflowStarter}: sync stub call ⇒ start workflow. */
    @Override
    public Long startFromStub(Class<?> iface, WorkflowOptions opts, Method method, Object[] args) {
        validateEntryMethod(iface, method);
        String workflowType = resolveType(iface);
        return writeStartState(workflowType, args).getId();
    }

    private <R> WorkflowHandle<R> startCapture(java.util.concurrent.Callable<Object> invocation) {
        WorkflowStubCallCapture.begin();
        WorkflowStubCallCapture.Capture cap;
        try {
            try {
                invocation.call();
            } catch (RuntimeException re) {
                throw re;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        } finally {
            cap = WorkflowStubCallCapture.end();
        }
        if (cap == null) {
            throw new IllegalStateException(
                    "WorkflowClient.start(...) did not see any workflow stub call. " +
                    "The lambda must invoke a @WorkflowMethod on a stub created via newWorkflowStub(...).");
        }
        validateEntryMethod(cap.iface(), cap.method());
        String workflowType = resolveType(cap.iface());
        WorkflowInstance wf = writeStartState(workflowType, cap.args());

        bindStub(cap.stubProxy(), wf.getId());

        return new WorkflowHandleImpl<>(
                wf.getId(),
                workflowType,
                cap.method().getGenericReturnType(),
                workflowRepository,
                objectMapper);
    }

    private void bindStub(Object stub, Long id) {
        if (stub == null) return;
        if (!Proxy.isProxyClass(stub.getClass())) return;
        Object h = Proxy.getInvocationHandler(stub);
        if (h instanceof WorkflowStubInvocationHandler<?> handler) {
            handler.bindInstanceId(id);
        }
    }

    private void validateEntryMethod(Class<?> iface, Method method) {
        if (!method.isAnnotationPresent(WorkflowMethod.class)) {
            throw new IllegalArgumentException(
                    "Method " + method + " is not annotated with @WorkflowMethod. " +
                    "WorkflowClient.start(...) requires a method reference to a @WorkflowMethod.");
        }
        if (!method.getDeclaringClass().isAssignableFrom(iface) && method.getDeclaringClass() != iface) {
            throw new IllegalArgumentException(
                    "Captured method " + method + " does not belong to interface " + iface.getName());
        }
    }

    private String resolveType(Class<?> iface) {
        String type = workflowRegistry.getTypeForInterface(iface);
        if (type == null) {
            throw new IllegalStateException(
                    "No @WorkflowComponent registered for interface " + iface.getName() +
                    ". Make sure an implementing bean annotated with @WorkflowComponent is on the classpath.");
        }
        return type;
    }

    @Transactional
    protected WorkflowInstance writeStartState(String workflowType, Object[] args) {
        if (args != null && args.length > 1) {
            throw new IllegalArgumentException(
                    "Workflow entry method must take 0 or 1 parameters; got " + args.length);
        }
        Object input = (args != null && args.length == 1) ? args[0] : null;

        WorkflowInstance wf = new WorkflowInstance();
        wf.setWorkflowType(workflowType);
        wf.setStatus(WorkflowStatus.PENDING);
        wf.setInput(serialize(input));
        wf.setCreatedAt(Instant.now());
        wf.setUpdatedAt(Instant.now());
        workflowRepository.save(wf);

        Event created = new Event();
        created.setWorkflowId(wf.getId());
        created.setEventType(EventType.WORKFLOW_CREATED);
        created.setPayload(wf.getInput());
        eventRepository.save(created);

        Task t = new Task();
        t.setWorkflowId(wf.getId());
        t.setTaskType("workflow.start");
        t.setStatus(TaskStatus.PENDING);
        t.setScheduledAt(Instant.now());
        taskRepository.save(t);

        Event queued = new Event();
        queued.setWorkflowId(wf.getId());
        queued.setEventType(EventType.WORKFLOW_TASK_QUEUED);
        queued.setPayload("{\"reason\":\"start\"}");
        eventRepository.save(queued);

        log.info("Workflow {} ({}) started, task {} enqueued", wf.getId(), workflowType, t.getId());
        return wf;
    }

    private String serialize(Object value) {
        if (value == null) return null;
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize workflow input", e);
        }
    }
}
