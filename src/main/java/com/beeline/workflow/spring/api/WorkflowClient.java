package com.beeline.workflow.spring.api;

public interface WorkflowClient {

    Long startWorkflow(String workflowType, Object input);
}
