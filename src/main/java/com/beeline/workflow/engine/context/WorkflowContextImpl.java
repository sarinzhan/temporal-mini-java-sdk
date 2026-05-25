package com.beeline.workflow.engine.context;

import com.beeline.workflow.core.api.WorkflowContext;
import com.beeline.workflow.engine.executor.ActivityExecutor;
import com.beeline.workflow.engine.replay.EventSink;
import com.beeline.workflow.engine.replay.HistoryCursor;
import com.beeline.workflow.engine.replay.TaskLease;
import com.beeline.workflow.engine.replay.WakeupRegistrar;
import com.beeline.workflow.registry.ActivityRegistry;

public final class WorkflowContextImpl implements WorkflowContext {

    private final Long workflowId;
    private final Long currentTaskId;
    private final ActivityExecutor activityExecutor;
    private final ActivityRegistry activityRegistry;
    private final HistoryCursor historyCursor;
    private final EventSink eventSink;
    private final WakeupRegistrar wakeupRegistrar;
    private final TaskLease taskLease;

    public WorkflowContextImpl(Long workflowId,
                               Long currentTaskId,
                               ActivityExecutor activityExecutor,
                               ActivityRegistry activityRegistry,
                               HistoryCursor historyCursor,
                               EventSink eventSink,
                               WakeupRegistrar wakeupRegistrar,
                               TaskLease taskLease) {
        this.workflowId = workflowId;
        this.currentTaskId = currentTaskId;
        this.activityExecutor = activityExecutor;
        this.activityRegistry = activityRegistry;
        this.historyCursor = historyCursor;
        this.eventSink = eventSink;
        this.wakeupRegistrar = wakeupRegistrar;
        this.taskLease = taskLease;
    }

    @Override public Long getWorkflowId() { return workflowId; }

    @Override public ActivityExecutor getActivityExecutor() { return activityExecutor; }

    @Override public ActivityRegistry getActivityRegistry() { return activityRegistry; }

    @Override public HistoryCursor getHistoryCursor() { return historyCursor; }

    @Override public EventSink getEventSink() { return eventSink; }

    @Override public WakeupRegistrar getWakeupRegistrar() { return wakeupRegistrar; }

    @Override public TaskLease getTaskLease() { return taskLease; }

    public Long getCurrentTaskId() { return currentTaskId; }
}
