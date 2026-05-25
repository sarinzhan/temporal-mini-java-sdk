package com.beeline.workflow.examples.order;

import com.beeline.workflow.core.annotation.WorkflowComponent;
import com.beeline.workflow.core.api.Workflow;

import java.time.Duration;

@WorkflowComponent
public class OrderWorkflowImpl implements OrderWorkflow {

    private volatile String approver;
    private volatile String state = "NEW";

    @Override
    public String process(OrderRequest request) {
        state = "WAITING_APPROVAL";
        boolean ok = Workflow.await(Duration.ofMinutes(5), () -> approver != null);
        if (!ok) {
            state = "REJECTED_TIMEOUT";
            return "rejected: no approver";
        }
        state = "APPROVED_BY_" + approver;
        return "approved by " + approver + " for order=" + request.getOrderId() + " amount=" + request.getAmount();
    }

    @Override
    public void approve(String approver) {
        this.approver = approver;
    }

    @Override
    public String getState() {
        return state;
    }
}
