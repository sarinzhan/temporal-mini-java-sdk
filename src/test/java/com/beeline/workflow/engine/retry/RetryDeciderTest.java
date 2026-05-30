package com.beeline.workflow.engine.retry;

import com.beeline.workflow.core.config.RetryPolicy;
import com.beeline.workflow.core.exception.NonRetryableException;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RetryDeciderTest {

    private final RetryDecider decider = new RetryDecider();

    @Test
    void nonRetryableExceptionTerminatesImmediatelyAsNonRetryable() {
        RetryPolicy policy = RetryPolicy.newBuilder().setMaxAttempts(5).build();

        RetryDecision d = decider.decide(new NonRetryableException("nope"), 1, policy);

        RetryDecision.Terminal t = assertInstanceOf(RetryDecision.Terminal.class, d);
        assertTrue(t.nonRetryable());
    }

    @Test
    void retriesWhenAttemptsRemain() {
        RetryPolicy policy = RetryPolicy.newBuilder().setMaxAttempts(3).build();

        RetryDecision d = decider.decide(new RuntimeException("transient"), 1, policy);

        assertInstanceOf(RetryDecision.Retry.class, d);
    }

    @Test
    void terminatesWhenAttemptsExhausted() {
        RetryPolicy policy = RetryPolicy.newBuilder().setMaxAttempts(3).build();

        RetryDecision d = decider.decide(new RuntimeException("transient"), 3, policy);

        RetryDecision.Terminal t = assertInstanceOf(RetryDecision.Terminal.class, d);
        assertFalse(t.nonRetryable());
    }

    @Test
    void respectsNoRetryList() {
        RetryPolicy policy = RetryPolicy.newBuilder()
                .setMaxAttempts(5)
                .addNoRetry(IllegalStateException.class)
                .build();

        RetryDecision d = decider.decide(new IllegalStateException("blocked"), 1, policy);

        assertInstanceOf(RetryDecision.Terminal.class, d);
    }

    @Test
    void computeFireAtIsInTheFuture() {
        var before = java.time.Instant.now();
        var fireAt = decider.computeFireAt(Duration.ofSeconds(10));
        assertTrue(!fireAt.isBefore(before.plusSeconds(9)));
    }
}
