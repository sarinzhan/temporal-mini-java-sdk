package com.beeline.workflow.core.api;

import com.beeline.workflow.engine.executor.ActivityExecutor;
import com.beeline.workflow.registry.ActivityRegistry;

import java.util.UUID;

public interface WorkflowContext {

    UUID getWorkflowId();

    ActivityExecutor getActivityExecutor();

    ActivityRegistry getActivityRegistry();
}
