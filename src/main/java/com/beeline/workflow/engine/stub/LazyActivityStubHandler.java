package com.beeline.workflow.engine.stub;

import com.beeline.workflow.core.config.ActivityOptions;
import com.beeline.workflow.engine.context.WorkflowContextHolder;
import com.beeline.workflow.engine.executor.ActivityExecutor;
import com.beeline.workflow.registry.ActivityRegistry;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
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

        return executor.execute(activityName, options, returnType, () -> {
            try {
                return method.invoke(bean, args);
            } catch (InvocationTargetException ite) {
                Throwable cause = ite.getCause() != null ? ite.getCause() : ite;
                if (cause instanceof RuntimeException re) throw re;
                if (cause instanceof Error err) throw err;
                throw new RuntimeException(cause);
            } catch (IllegalAccessException iae) {
                throw new RuntimeException(iae);
            }
        });
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
