package com.beeline.workflow.engine.stub;

import com.beeline.workflow.core.annotation.QueryMethod;
import com.beeline.workflow.core.annotation.SignalMethod;
import com.beeline.workflow.core.annotation.UpdateMethod;
import com.beeline.workflow.core.annotation.WorkflowMethod;
import com.beeline.workflow.core.config.WorkflowOptions;
import com.beeline.workflow.engine.update.UpdateRegistry;
import com.beeline.workflow.web.service.WorkflowInvocationService;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.ObjectMapper;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.List;

public class WorkflowStubInvocationHandler<T> implements InvocationHandler {

    private static final long DEFAULT_UPDATE_TIMEOUT_MS = 30_000L;

    private final Class<T> iface;
    private final WorkflowOptions opts;
    private volatile Long boundInstanceId;

    public WorkflowStubInvocationHandler(Class<T> iface, WorkflowOptions opts, Long boundInstanceId) {
        this.iface = iface;
        this.opts = opts;
        this.boundInstanceId = boundInstanceId;
    }

    public Class<T> getIface() { return iface; }
    public WorkflowOptions getOptions() { return opts; }
    public Long getBoundInstanceId() { return boundInstanceId; }
    public void bindInstanceId(Long id) { this.boundInstanceId = id; }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) {
        if (method.getDeclaringClass() == Object.class) {
            return invokeObjectMethod(proxy, method, args);
        }

        if (WorkflowStubCallCapture.isCapturing()) {
            WorkflowStubCallCapture.record(opts, iface, method, args, proxy);
            return defaultValue(method.getReturnType());
        }

        if (method.isAnnotationPresent(WorkflowMethod.class)) {
            return invokeWorkflowSync(method, args);
        }
        if (method.isAnnotationPresent(SignalMethod.class)) {
            return invokeSignal(method, args);
        }
        if (method.isAnnotationPresent(QueryMethod.class)) {
            return invokeQuery(method, args);
        }
        if (method.isAnnotationPresent(UpdateMethod.class)) {
            return invokeUpdate(method, args);
        }
        throw new UnsupportedOperationException(
                "Workflow stub method has no @WorkflowMethod/@SignalMethod/@QueryMethod/@UpdateMethod: "
                        + method);
    }

    private Object invokeWorkflowSync(Method method, Object[] args) {
        Long id = WorkflowStubFactory.requireStarter()
                .startFromStub(iface, opts, method, args);
        bindInstanceId(id);
        // Block until terminal status via DB poll.
        com.beeline.workflow.spring.api.WorkflowHandleImpl<Object> handle =
                new com.beeline.workflow.spring.api.WorkflowHandleImpl<>(
                        id,
                        WorkflowStubFactory.requireWorkflowRegistry().getTypeForInterface(iface),
                        method.getGenericReturnType(),
                        WorkflowStubFactory.requireWorkflowRepository(),
                        WorkflowStubFactory.requireObjectMapper());
        return handle.getResult(Long.MAX_VALUE);
    }

    private Object invokeSignal(Method method, Object[] args) {
        requireBound("signal");
        SignalMethod ann = method.getAnnotation(SignalMethod.class);
        String name = (ann.name() == null || ann.name().isBlank()) ? method.getName() : ann.name();
        Object payload = (args != null && args.length > 0) ? args[0] : null;
        WorkflowStubFactory.requireSignalBus().send(boundInstanceId, name, payload);
        return defaultValue(method.getReturnType());
    }

    private Object invokeQuery(Method method, Object[] args) {
        requireBound("query");
        QueryMethod ann = method.getAnnotation(QueryMethod.class);
        String name = (ann.name() == null || ann.name().isBlank()) ? method.getName() : ann.name();
        List<Object> argList = (args == null || args.length == 0) ? List.of() : Arrays.asList(args);
        Object raw = WorkflowStubFactory.requireQueryRuntime()
                .runQuery(boundInstanceId, name, argList);
        return coerceReturn(raw, method.getGenericReturnType());
    }

    private Object invokeUpdate(Method method, Object[] args) {
        requireBound("update");
        UpdateMethod ann = method.getAnnotation(UpdateMethod.class);
        String name = (ann.name() == null || ann.name().isBlank()) ? method.getName() : ann.name();
        List<Object> argList = (args == null || args.length == 0) ? List.of() : Arrays.asList(args);
        WorkflowInvocationService svc = WorkflowStubFactory.requireInvocationService();
        String updateId = svc.dispatchUpdate(boundInstanceId, name, argList);
        UpdateRegistry.UpdateResult result = svc.awaitUpdate(updateId, DEFAULT_UPDATE_TIMEOUT_MS);
        if (!result.success()) {
            throw new RuntimeException("update failed: " + result.error());
        }
        return coerceReturn(result.value(), method.getGenericReturnType());
    }

    private void requireBound(String op) {
        if (boundInstanceId == null) {
            throw new IllegalStateException(
                    "Stub is not bound to a workflow instance — cannot perform " + op +
                    ". Call WorkflowClient.start(stub::...) first, or create the stub via " +
                    "client.newWorkflowStub(iface, instanceId).");
        }
    }

    private Object coerceReturn(Object raw, Type target) {
        if (target == void.class || target == Void.class) return null;
        if (raw == null) return null;
        Class<?> rawClass = raw.getClass();
        if (target instanceof Class<?> tc && tc.isAssignableFrom(rawClass)) {
            return raw;
        }
        // Round-trip through JSON to coerce types deterministically.
        ObjectMapper mapper = WorkflowStubFactory.requireObjectMapper();
        try {
            String json = mapper.writeValueAsString(raw);
            JavaType jt = mapper.constructType(target);
            return mapper.readValue(json, jt);
        } catch (Exception e) {
            throw new RuntimeException("Failed to coerce return to " + target.getTypeName(), e);
        }
    }

    private Object invokeObjectMethod(Object proxy, Method method, Object[] args) {
        return switch (method.getName()) {
            case "toString" -> "WorkflowStub(" + iface.getName() + (boundInstanceId != null ? ", id=" + boundInstanceId : "") + ")";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> args != null && args.length == 1 && args[0] == proxy;
            default -> throw new UnsupportedOperationException("Method not supported on workflow stub: " + method.getName());
        };
    }

    private static Object defaultValue(Class<?> returnType) {
        if (!returnType.isPrimitive() || returnType == void.class) return null;
        if (returnType == boolean.class) return false;
        if (returnType == char.class) return (char) 0;
        if (returnType == byte.class) return (byte) 0;
        if (returnType == short.class) return (short) 0;
        if (returnType == int.class) return 0;
        if (returnType == long.class) return 0L;
        if (returnType == float.class) return 0f;
        if (returnType == double.class) return 0d;
        return null;
    }
}
