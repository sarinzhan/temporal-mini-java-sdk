package com.beeline.workflow.spring.api;

import com.beeline.workflow.core.model.WorkflowStatus;

public interface WorkflowHandle<T> {

    Long getInstanceId();

    String getWorkflowType();

    WorkflowStatus getStatus();

    T getResult(long timeoutMs);

    /** Block (up to ~max long ms) until the workflow completes, then return its result. */
    default T getResult() {
        return getResult(Long.MAX_VALUE);
    }
}
