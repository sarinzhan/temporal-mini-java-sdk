package com.beeline.workflow.core.api;

import com.beeline.workflow.engine.executor.ActivityExecutor;
import com.beeline.workflow.engine.replay.EventSink;
import com.beeline.workflow.engine.replay.HistoryCursor;
import com.beeline.workflow.engine.replay.TaskLease;

public interface WorkflowContext {

    Long getWorkflowId();

    ActivityExecutor getActivityExecutor();

    HistoryCursor getHistoryCursor();

    EventSink getEventSink();

    /** Lease for the task driving this turn; used to fence writes against a lost lock. */
    TaskLease getTaskLease();
}
