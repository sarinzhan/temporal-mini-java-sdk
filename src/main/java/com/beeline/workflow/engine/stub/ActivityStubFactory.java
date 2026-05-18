package com.beeline.workflow.engine.stub;

import com.beeline.workflow.core.annotation.Activity;
import com.beeline.workflow.core.config.ActivityOptions;
import com.beeline.workflow.engine.executor.ActivityExecutor;
import com.beeline.workflow.registry.ActivityRegistry;

import java.lang.reflect.Proxy;

public class ActivityStubFactory {

    private static volatile ActivityExecutor STATIC_EXECUTOR;
    private static volatile ActivityRegistry STATIC_REGISTRY;

    public ActivityStubFactory(ActivityExecutor executor, ActivityRegistry registry) {
        STATIC_EXECUTOR = executor;
        STATIC_REGISTRY = registry;
    }

    public <T> T createLazyStub(Class<T> activityInterface, ActivityOptions options) {
        return createStub(activityInterface, options);
    }

    public static <T> T createStub(Class<T> activityInterface, ActivityOptions options) {
        if (activityInterface == null) {
            throw new IllegalArgumentException("activityInterface must not be null");
        }
        if (!activityInterface.isInterface()) {
            throw new IllegalArgumentException(
                    "Activity stub target must be an interface: " + activityInterface.getName());
        }
        if (!activityInterface.isAnnotationPresent(Activity.class)) {
            throw new IllegalArgumentException(
                    "Activity stub target must be annotated with @Activity: " + activityInterface.getName());
        }
        ActivityOptions opts = options != null ? options : ActivityOptions.defaultOptions();
        LazyActivityStubHandler<T> handler = new LazyActivityStubHandler<>(activityInterface, opts);
        Object proxy = Proxy.newProxyInstance(
                activityInterface.getClassLoader(),
                new Class<?>[]{activityInterface},
                handler);
        return activityInterface.cast(proxy);
    }

    public static ActivityExecutor requireExecutor() {
        ActivityExecutor e = STATIC_EXECUTOR;
        if (e == null) {
            throw new IllegalStateException(
                    "ActivityStubFactory is not initialized. The Spring autoconfigure must construct an " +
                    "ActivityStubFactory bean before activity stubs are invoked.");
        }
        return e;
    }

    public static ActivityRegistry requireRegistry() {
        ActivityRegistry r = STATIC_REGISTRY;
        if (r == null) {
            throw new IllegalStateException(
                    "ActivityStubFactory is not initialized. The Spring autoconfigure must construct an " +
                    "ActivityStubFactory bean before activity stubs are invoked.");
        }
        return r;
    }
}
