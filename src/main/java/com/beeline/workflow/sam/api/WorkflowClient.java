package com.beeline.workflow.sam.api;

import com.beeline.workflow.core.annotation.WorkflowMethod;
import com.beeline.workflow.core.api.WorkflowEngine;

public interface WorkflowClient {

    public <T> T newWorkflowStub(Class<T> iface);
}
