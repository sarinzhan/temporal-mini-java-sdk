package com.beeline.workflow.engine.executor;

import com.beeline.workflow.core.config.ActivityOptions;

import java.lang.reflect.Method;
import java.lang.reflect.Type;

/**
 * Workflow-side entry point for activity invocation. Unlike a direct call, this does <b>not</b>
 * run the activity on the workflow thread. On a fresh (or retrying) activity it records
 * {@code ACTIVITY_SCHEDULED}, creates an {@code activity} {@link com.beeline.workflow.core.model.Task}
 * for a separate worker to run, and parks the workflow turn. On replay it returns the cached
 * result (or re-throws the recorded terminal failure) without scheduling anything.
 *
 * <p>The activity is identified durably by {@code activityInterface}/{@code method}/{@code args}
 * so any worker (this node or another) can resolve and execute it from the persisted task payload.
 */
public interface ActivityExecutor {

    Object execute(String activityName,
                   ActivityOptions options,
                   Type returnType,
                   Class<?> activityInterface,
                   Method method,
                   Object[] args);
}
