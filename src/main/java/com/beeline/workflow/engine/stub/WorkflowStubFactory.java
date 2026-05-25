package com.beeline.workflow.engine.stub;

import com.beeline.workflow.core.annotation.WorkflowInterface;
import com.beeline.workflow.core.config.WorkflowOptions;
import com.beeline.workflow.engine.query.WorkflowQueryRuntime;
import com.beeline.workflow.engine.signal.SignalBus;
import com.beeline.workflow.engine.update.UpdateRegistry;
import com.beeline.workflow.persistence.repository.WorkflowRepository;
import com.beeline.workflow.registry.WorkflowRegistry;
import com.beeline.workflow.web.service.WorkflowInvocationService;
import tools.jackson.databind.ObjectMapper;

import java.lang.reflect.Proxy;

public class WorkflowStubFactory {

    private static volatile SignalBus STATIC_SIGNAL_BUS;
    private static volatile WorkflowQueryRuntime STATIC_QUERY_RUNTIME;
    private static volatile WorkflowInvocationService STATIC_INVOCATION_SERVICE;
    private static volatile UpdateRegistry STATIC_UPDATE_REGISTRY;
    private static volatile WorkflowRegistry STATIC_WORKFLOW_REGISTRY;
    private static volatile WorkflowRepository STATIC_WORKFLOW_REPOSITORY;
    private static volatile ObjectMapper STATIC_OBJECT_MAPPER;
    private static volatile WorkflowStarter STATIC_STARTER;

    public WorkflowStubFactory(SignalBus signalBus,
                               WorkflowQueryRuntime queryRuntime,
                               WorkflowInvocationService invocationService,
                               UpdateRegistry updateRegistry,
                               WorkflowRegistry workflowRegistry,
                               WorkflowRepository workflowRepository,
                               ObjectMapper objectMapper,
                               WorkflowStarter starter) {
        STATIC_SIGNAL_BUS = signalBus;
        STATIC_QUERY_RUNTIME = queryRuntime;
        STATIC_INVOCATION_SERVICE = invocationService;
        STATIC_UPDATE_REGISTRY = updateRegistry;
        STATIC_WORKFLOW_REGISTRY = workflowRegistry;
        STATIC_WORKFLOW_REPOSITORY = workflowRepository;
        STATIC_OBJECT_MAPPER = objectMapper;
        STATIC_STARTER = starter;
    }

    public static <T> T create(Class<T> iface, WorkflowOptions opts, Long boundInstanceId) {
        if (iface == null) {
            throw new IllegalArgumentException("workflow interface must not be null");
        }
        if (!iface.isInterface()) {
            throw new IllegalArgumentException(
                    "Workflow stub target must be an interface: " + iface.getName());
        }
        if (!iface.isAnnotationPresent(WorkflowInterface.class)) {
            throw new IllegalArgumentException(
                    "Workflow stub target must be annotated with @WorkflowInterface: " + iface.getName());
        }
        WorkflowOptions effective = opts != null ? opts : WorkflowOptions.defaultOptions();
        WorkflowStubInvocationHandler<T> handler =
                new WorkflowStubInvocationHandler<>(iface, effective, boundInstanceId);
        Object proxy = Proxy.newProxyInstance(
                iface.getClassLoader(),
                new Class<?>[]{iface},
                handler);
        return iface.cast(proxy);
    }

    static SignalBus requireSignalBus() { return require(STATIC_SIGNAL_BUS, "SignalBus"); }
    static WorkflowQueryRuntime requireQueryRuntime() { return require(STATIC_QUERY_RUNTIME, "WorkflowQueryRuntime"); }
    static WorkflowInvocationService requireInvocationService() { return require(STATIC_INVOCATION_SERVICE, "WorkflowInvocationService"); }
    static UpdateRegistry requireUpdateRegistry() { return require(STATIC_UPDATE_REGISTRY, "UpdateRegistry"); }
    static WorkflowRegistry requireWorkflowRegistry() { return require(STATIC_WORKFLOW_REGISTRY, "WorkflowRegistry"); }
    static WorkflowRepository requireWorkflowRepository() { return require(STATIC_WORKFLOW_REPOSITORY, "WorkflowRepository"); }
    static ObjectMapper requireObjectMapper() { return require(STATIC_OBJECT_MAPPER, "ObjectMapper"); }
    static WorkflowStarter requireStarter() { return require(STATIC_STARTER, "WorkflowStarter"); }

    private static <X> X require(X v, String label) {
        if (v == null) {
            throw new IllegalStateException(
                    "WorkflowStubFactory is not initialized — " + label + " missing. " +
                    "Spring autoconfigure must construct WorkflowStubFactory before stubs are invoked.");
        }
        return v;
    }

    /**
     * Internal bridge so a direct (non-capture) call on a {@code @WorkflowMethod} stub
     * can trigger the same start path as {@code WorkflowClient.start(...)}.
     */
    public interface WorkflowStarter {
        Long startFromStub(Class<?> iface,
                           WorkflowOptions opts,
                           java.lang.reflect.Method method,
                           Object[] args);
    }
}
