package com.beeline.workflow.core.model;

public enum EventType {
    // Lifecycle
    WORKFLOW_CREATED,
    WORKFLOW_TASK_QUEUED, // убрать
    WORKFLOW_TASK_STARTED,
    WORKFLOW_TASK_COMPLETED, // добавить WORKFLOW_TASK_FAILED
    WORKFLOW_COMPLETED,
    WORKFLOW_FAILED,

    // Activities (run inline; retry waits via a parked turn + schedule row)
    ACTIVITY_STARTED, ACTIVITY_COMPLETED,
    ACTIVITY_FAILED, // ACTIVITY_TIMEOUT
    ACTIVITY_RETRY_SCHEDULED, // ACTIVITY_SCHEDULED

    // Determinism helpers
    SIDE_EFFECT_RECORDED,
    VERSION_MARKER
}
