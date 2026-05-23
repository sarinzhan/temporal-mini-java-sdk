package com.beeline.workflow.core.api;

import com.beeline.workflow.engine.executor.ActivityExecutor;
import com.beeline.workflow.engine.replay.EventSink;
import com.beeline.workflow.engine.replay.HistoryCursor;
import com.beeline.workflow.engine.replay.WakeupRegistrar;
import com.beeline.workflow.registry.ActivityRegistry;

public interface WorkflowContext {

    Long getWorkflowId();

    ActivityExecutor getActivityExecutor();

    ActivityRegistry getActivityRegistry();

    HistoryCursor getHistoryCursor();

    EventSink getEventSink();

    WakeupRegistrar getWakeupRegistrar();
}
