package com.beeline.workflow.core.model;

public enum EventType {
    // Lifecycle
    WORKFLOW_CREATED,
    WORKFLOW_TASK_QUEUED,
    WORKFLOW_TASK_STARTED,
    WORKFLOW_TASK_COMPLETED,
    WORKFLOW_COMPLETED,
    WORKFLOW_FAILED,
    WORKFLOW_CANCELLED,

    // Activities
    ACTIVITY_SCHEDULED,
    ACTIVITY_STARTED,
    ACTIVITY_COMPLETED,
    ACTIVITY_FAILED,
    ACTIVITY_RETRY_SCHEDULED,

    // Timers (Workflow.sleep)
    TIMER_STARTED,
    TIMER_FIRED,

    // Awaits (Workflow.await)
    AWAIT_BLOCKED,
    AWAIT_FIRED,

    // Signals
    SIGNAL_RECEIVED,

    // Updates (@UpdateMethod)
    UPDATE_REQUESTED,
    UPDATE_COMPLETED,

    // Determinism helpers
    SIDE_EFFECT_RECORDED,
    VERSION_MARKER
}
