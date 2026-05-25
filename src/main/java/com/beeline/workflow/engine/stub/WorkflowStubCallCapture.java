package com.beeline.workflow.engine.stub;

import com.beeline.workflow.core.config.WorkflowOptions;

import java.lang.reflect.Method;

public final class WorkflowStubCallCapture {

    public record Capture(WorkflowOptions opts, Class<?> iface, Method method, Object[] args, Object stubProxy) {}

    private static final ThreadLocal<Capture> TL = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> ACTIVE = ThreadLocal.withInitial(() -> false);

    private WorkflowStubCallCapture() {}

    public static void begin() {
        if (Boolean.TRUE.equals(ACTIVE.get())) {
            throw new IllegalStateException(
                    "Nested WorkflowClient.start(...) detected. Capture is already active on this thread.");
        }
        ACTIVE.set(true);
        TL.remove();
    }

    public static boolean isCapturing() {
        return Boolean.TRUE.equals(ACTIVE.get());
    }

    public static void record(WorkflowOptions opts, Class<?> iface, Method method, Object[] args, Object stubProxy) {
        if (!isCapturing()) {
            throw new IllegalStateException("record() called outside capture mode");
        }
        if (TL.get() != null) {
            throw new IllegalStateException(
                    "Multiple workflow stub calls inside a single start(...): only one @WorkflowMethod call is allowed.");
        }
        TL.set(new Capture(opts, iface, method, args != null ? args : new Object[0], stubProxy));
    }

    public static Capture end() {
        try {
            return TL.get();
        } finally {
            TL.remove();
            ACTIVE.set(false);
        }
    }
}
