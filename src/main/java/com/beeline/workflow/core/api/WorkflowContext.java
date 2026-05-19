package com.beeline.workflow.core.api;

import com.beeline.workflow.engine.executor.ActivityExecutor;
import com.beeline.workflow.registry.ActivityRegistry;

public interface WorkflowContext {

    Long getWorkflowId();

    ActivityExecutor getActivityExecutor();

    ActivityRegistry getActivityRegistry();
}
