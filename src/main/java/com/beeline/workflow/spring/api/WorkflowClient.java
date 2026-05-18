package com.beeline.workflow.spring.api;

import java.util.UUID;

public interface WorkflowClient {

    UUID startWorkflow(String workflowType, Object input);
}
