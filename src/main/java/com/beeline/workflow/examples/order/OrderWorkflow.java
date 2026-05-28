package com.beeline.workflow.examples.order;

import com.beeline.workflow.core.annotation.WorkflowInterface;
import com.beeline.workflow.core.annotation.WorkflowMethod;

@WorkflowInterface
public interface OrderWorkflow {

    @WorkflowMethod
    String process(OrderRequest request);
}
