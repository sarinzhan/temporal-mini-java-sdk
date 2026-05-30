package com.beeline.workflow.examples.order;

import com.beeline.workflow.core.annotation.WorkflowComponent;
import com.beeline.workflow.core.api.Workflow;
import com.beeline.workflow.core.config.ActivityOptions;
import com.beeline.workflow.core.config.RetryPolicy;

import java.time.Duration;

@WorkflowComponent
public class OrderWorkflowImpl implements OrderWorkflow {

    private final OrderActivities activities;

    public OrderWorkflowImpl(OrderActivities activities) {
        this.activities = activities;
    }

    @Override
    public String process(OrderRequest req) {
        ActivityOptions opts = ActivityOptions.newBuilder()
                .setStartToCloseTimeout(Duration.ofSeconds(30))
                .setRetryPolicy(RetryPolicy.newBuilder()
                        .setMaxAttempts(5)
                        .setInitialInterval(Duration.ofSeconds(1))
                        .setBackoffCoefficient(2.0)
                        .setMaxInterval(Duration.ofSeconds(20))
                        .addNoRetry(IllegalArgumentException.class)   // never retry these
                        .build())
                .build();

        // Value activity (Supplier): closure captures the injected bean + request.
        // Pass the explicit return type so replay decodes deterministically.
        String reservationId = Workflow.activity("reserve", opts, String.class,
                () -> activities.reserve(req.getOrderId(), req.getAmount()));

        String txnId = Workflow.activity("charge", opts, String.class,
                () -> activities.charge(reservationId, req.getAmount()));

        // Void activity (Runnable), default options.
        Workflow.activity("notify", () -> activities.notifyCustomer(req.getOrderId(), txnId));

        return "order " + req.getOrderId() + " charged: " + txnId;
    }
}
