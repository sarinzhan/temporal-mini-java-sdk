package com.beeline.workflow.engine.context;

import com.beeline.workflow.core.api.ActivityExecution;

/**
 * ThreadLocal carrier for the {@link ActivityExecution} of the activity currently running. Unlike
 * {@link WorkflowContextHolder} (bound on the {@code wf-worker-*} thread for the whole turn), this is
 * bound on the {@code wf-activity-*} pool thread by {@code ActivityCommandHandler} just around the
 * body invocation — because the body runs on a different thread than the workflow code. Cleared in a
 * {@code finally} so a pooled thread never leaks one activity's context into the next.
 */
public final class ActivityExecutionContext {

    private static final ThreadLocal<ActivityExecution> CURRENT = new ThreadLocal<>();

    private ActivityExecutionContext() {}

    public static ActivityExecution require() {
        ActivityExecution exec = CURRENT.get();
        if (exec == null) {
            throw new IllegalStateException(
                    "No activity context bound to the current thread. " +
                    "Workflow.currentActivity()/currentActivityKey() may only be called from inside an activity body.");
        }
        return exec;
    }

    public static ActivityExecution current() {
        return CURRENT.get();
    }

    public static void set(ActivityExecution exec) {
        CURRENT.set(exec);
    }

    public static void clear() {
        CURRENT.remove();
    }
}
