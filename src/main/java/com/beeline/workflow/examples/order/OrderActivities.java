package com.beeline.workflow.examples.order;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Plain Spring bean holding the example's side-effecting work. Workflow code calls these via inline
 * lambdas — {@code Workflow.activity(() -> billing.reserve(...))} — so the engine records each
 * result to history and replays it instead of re-running on resume.
 */
@Service
public class OrderActivities {

    private static final Logger log = LoggerFactory.getLogger(OrderActivities.class);

    public String reserve(String orderId, double amount) {
        log.info("reserve order={} amount={}", orderId, amount);
        return "RES-" + orderId;
    }

    public String charge(String reservationId, double amount) {
        log.info("charge reservation={} amount={}", reservationId, amount);
        return "TXN-" + reservationId;
    }

    public void notifyCustomer(String orderId, String txnId) {
        log.info("notify order={} txn={}", orderId, txnId);
    }
}
