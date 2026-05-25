package com.beeline.workflow.examples.order;

import com.beeline.workflow.core.annotation.QueryMethod;
import com.beeline.workflow.core.annotation.SignalMethod;
import com.beeline.workflow.core.annotation.WorkflowInterface;
import com.beeline.workflow.core.annotation.WorkflowMethod;

@WorkflowInterface
public interface OrderWorkflow {

    @WorkflowMethod
    String process(OrderRequest request);

    @SignalMethod
    void approve(String approver);

    @QueryMethod(name = "state")
    String getState();
}
