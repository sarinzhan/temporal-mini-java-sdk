package com.beeline.workflow.engine.context;

import com.beeline.workflow.core.api.WorkflowContext;
import com.beeline.workflow.engine.executor.ActivityExecutor;
import com.beeline.workflow.registry.ActivityRegistry;

public final class WorkflowContextImpl implements WorkflowContext {

    private final Long workflowId;
    private final Long currentTaskId;
    private final ActivityExecutor activityExecutor;
    private final ActivityRegistry activityRegistry;

    public WorkflowContextImpl(Long workflowId,
                               Long currentTaskId,
                               ActivityExecutor activityExecutor,
                               ActivityRegistry activityRegistry) {
        this.workflowId = workflowId;
        this.currentTaskId = currentTaskId;
        this.activityExecutor = activityExecutor;
        this.activityRegistry = activityRegistry;
    }

    @Override public Long getWorkflowId() { return workflowId; }

    @Override public ActivityExecutor getActivityExecutor() { return activityExecutor; }

    @Override public ActivityRegistry getActivityRegistry() { return activityRegistry; }

    public Long getCurrentTaskId() { return currentTaskId; }
}
