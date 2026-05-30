package com.beeline.workflow.it.support;

import com.beeline.workflow.core.annotation.WorkflowInterface;
import com.beeline.workflow.core.annotation.WorkflowMethod;

@WorkflowInterface("ScenarioWorkflow")
public interface ScenarioWorkflow {

    @WorkflowMethod
    String run(Scenario scenario);
}
