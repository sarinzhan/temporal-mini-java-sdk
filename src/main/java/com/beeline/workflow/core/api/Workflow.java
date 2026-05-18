package com.beeline.workflow.core.api;

import com.beeline.workflow.core.config.ActivityOptions;
import com.beeline.workflow.engine.context.WorkflowContextHolder;
import com.beeline.workflow.engine.executor.ActivityExecutor;
import com.beeline.workflow.engine.signal.SignalBus;
import com.beeline.workflow.engine.stub.ActivityStubFactory;

import java.time.Duration;
import java.util.function.Function;
import java.util.function.Supplier;

public final class Workflow {

    private Workflow() {}

    private static volatile SignalBus signalBus;

    public static void installSignalBus(SignalBus bus) {
        Workflow.signalBus = bus;
    }

    // ── Typed-interface stub (JDK Proxy) ────────────────────────────────────

    public static <T> T newActivityStub(Class<T> activityInterface) {
        return newActivityStub(activityInterface, ActivityOptions.defaultOptions());
    }

    public static <T> T newActivityStub(Class<T> activityInterface, ActivityOptions options) {
        return ActivityStubFactory.createStub(activityInterface, options);
    }

    // ── Functional API — like the old ctx.activity(...) ─────────────────────

    public static <T> T activity(String name, Supplier<T> fn) {
        return activity(name, ActivityOptions.defaultOptions(), fn);
    }

    @SuppressWarnings("unchecked")
    public static <T> T activity(String name, ActivityOptions options, Supplier<T> fn) {
        WorkflowContextHolder.require();
        ActivityExecutor executor = ActivityStubFactory.requireExecutor();
        return (T) executor.execute(name, options, null, fn::get);
    }

    public static <I, O> O activity(String name, I input, Function<I, O> fn) {
        return activity(name, ActivityOptions.defaultOptions(), input, fn);
    }

    @SuppressWarnings("unchecked")
    public static <I, O> O activity(String name, ActivityOptions options, I input, Function<I, O> fn) {
        WorkflowContextHolder.require();
        ActivityExecutor executor = ActivityStubFactory.requireExecutor();
        return (O) executor.execute(name, options, null, () -> fn.apply(input));
    }

    public static void activity(String name, Runnable fn) {
        activity(name, ActivityOptions.defaultOptions(), fn);
    }

    public static void activity(String name, ActivityOptions options, Runnable fn) {
        WorkflowContextHolder.require();
        ActivityExecutor executor = ActivityStubFactory.requireExecutor();
        executor.execute(name, options, void.class, () -> { fn.run(); return null; });
    }

    // ── Timers + signals ────────────────────────────────────────────────────

    public static void sleep(Duration duration) {
        WorkflowContextHolder.require();
        try {
            Thread.sleep(Math.max(0L, duration.toMillis()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Workflow.sleep interrupted", e);
        }
    }

    public static Object waitForSignal(String signalName) {
        return waitForSignal(signalName, Duration.ofMinutes(5));
    }

    public static Object waitForSignal(String signalName, Duration timeout) {
        SignalBus bus = signalBus;
        if (bus == null) {
            throw new IllegalStateException(
                    "SignalBus is not initialized. The Spring autoconfigure must call Workflow.installSignalBus().");
        }
        WorkflowContext ctx = WorkflowContextHolder.require();
        return bus.await(ctx.getWorkflowId(), signalName, timeout);
    }
}
