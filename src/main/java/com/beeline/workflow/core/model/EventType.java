package com.beeline.workflow.core.model;

public enum EventType {
    // Lifecycle
    WORKFLOW_CREATED,
    WORKFLOW_TASK_QUEUED,
    WORKFLOW_TASK_STARTED,
    WORKFLOW_TASK_COMPLETED,
    WORKFLOW_COMPLETED,
    WORKFLOW_FAILED,

    // Activities (run inline; retry waits via a parked turn + schedule row)
    ACTIVITY_STARTED,
    ACTIVITY_COMPLETED,
    ACTIVITY_FAILED,
    ACTIVITY_RETRY_SCHEDULED,

    // Determinism helpers
    SIDE_EFFECT_RECORDED,
    VERSION_MARKER
}
