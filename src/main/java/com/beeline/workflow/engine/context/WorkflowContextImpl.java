package com.beeline.workflow.engine.context;

import com.beeline.workflow.core.api.WorkflowContext;
import com.beeline.workflow.engine.executor.ActivityExecutor;
import com.beeline.workflow.registry.ActivityRegistry;

import java.util.UUID;

public final class WorkflowContextImpl implements WorkflowContext {

    private final UUID workflowId;
    private final UUID currentTaskId;
    private final ActivityExecutor activityExecutor;
    private final ActivityRegistry activityRegistry;

    public WorkflowContextImpl(UUID workflowId,
                               UUID currentTaskId,
                               ActivityExecutor activityExecutor,
                               ActivityRegistry activityRegistry) {
        this.workflowId = workflowId;
        this.currentTaskId = currentTaskId;
        this.activityExecutor = activityExecutor;
        this.activityRegistry = activityRegistry;
    }

    @Override public UUID getWorkflowId() { return workflowId; }

    @Override public ActivityExecutor getActivityExecutor() { return activityExecutor; }

    @Override public ActivityRegistry getActivityRegistry() { return activityRegistry; }

    public UUID getCurrentTaskId() { return currentTaskId; }
}
