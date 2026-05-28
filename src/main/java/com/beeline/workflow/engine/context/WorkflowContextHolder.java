package com.beeline.workflow.engine.context;

import com.beeline.workflow.core.api.WorkflowContext;

public final class WorkflowContextHolder {

    private static final ThreadLocal<WorkflowContext> CONTEXT = new ThreadLocal<>();

    private WorkflowContextHolder() {}

    public static WorkflowContext require() {
        WorkflowContext ctx = CONTEXT.get();
        if (ctx == null) {
            throw new IllegalStateException(
                    "No workflow context bound to the current thread. " +
                    "Workflow facade methods (activity, sideEffect, getVersion) may only be invoked from within a workflow execution.");
        }
        return ctx;
    }

    public static WorkflowContext current() {
        return CONTEXT.get();
    }

    public static void set(WorkflowContext ctx) {
        CONTEXT.set(ctx);
    }

    public static void clear() {
        CONTEXT.remove();
    }
}
