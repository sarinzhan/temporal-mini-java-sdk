package com.beeline.workflow.spring.api;

import com.beeline.workflow.core.model.WorkflowStatus;

public interface WorkflowHandle<T> {

    Long getInstanceId();

    String getWorkflowType();

    WorkflowStatus getStatus();

    T getResult(long timeoutMs);
}
