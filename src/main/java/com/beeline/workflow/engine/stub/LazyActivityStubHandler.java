package com.beeline.workflow.engine.stub;

import com.beeline.workflow.core.config.ActivityOptions;
import com.beeline.workflow.engine.context.WorkflowContextHolder;
import com.beeline.workflow.engine.executor.ActivityExecutor;
import com.beeline.workflow.registry.ActivityRegistry;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Type;

public class LazyActivityStubHandler<T> implements InvocationHandler {

    private final Class<T> activityInterface;
    private final ActivityOptions options;

    public LazyActivityStubHandler(Class<T> activityInterface, ActivityOptions options) {
        this.activityInterface = activityInterface;
        this.options = options;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) {
        if (method.getDeclaringClass() == Object.class) {
            return invokeObjectMethod(proxy, method, args);
        }
        WorkflowContextHolder.require();

        ActivityExecutor executor = ActivityStubFactory.requireExecutor();
        ActivityRegistry registry = ActivityStubFactory.requireRegistry();

        Object bean = registry.getBean(activityInterface);
        if (bean == null) {
            throw new IllegalStateException(
                    "No activity bean registered for interface " + activityInterface.getName() +
                    ". Make sure a Spring bean implementing it is on the classpath.");
        }

        String activityName = buildActivityName(method);
        Type returnType = method.getGenericReturnType();

        // The activity does not run here. The executor records ACTIVITY_SCHEDULED, creates an
        // 'activity' Task carrying the interface/method/args, and parks the workflow; a worker
        // resolves and runs it on a separate thread. We pass the resolution metadata + args so the
        // call can be reconstructed durably from the task payload.
        Object[] callArgs = args != null ? args : new Object[0];
        return executor.execute(activityName, options, returnType, activityInterface, method, callArgs);
    }

    private String buildActivityName(Method method) {
        return activityInterface.getSimpleName() + "." + method.getName();
    }

    private Object invokeObjectMethod(Object proxy, Method method, Object[] args) {
        String name = method.getName();
        return switch (name) {
            case "toString" -> "ActivityStub(" + activityInterface.getName() + ")";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals"   -> args != null && args.length == 1 && args[0] == proxy;
            default -> throw new UnsupportedOperationException("Method not supported on activity stub: " + name);
        };
    }
}
