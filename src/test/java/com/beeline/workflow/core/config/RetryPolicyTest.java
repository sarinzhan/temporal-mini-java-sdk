package com.beeline.workflow.core.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RetryPolicyTest {

    @Test
    void exponentialBackoffGrowsByCoefficient() {
        RetryPolicy p = RetryPolicy.newBuilder()
                .setInitialInterval(Duration.ofSeconds(1))
                .setBackoffCoefficient(2.0)
                .setMaxInterval(Duration.ofMinutes(10))
                .build();

        assertEquals(Duration.ofSeconds(1), p.nextDelay(0));
        assertEquals(Duration.ofSeconds(2), p.nextDelay(1));
        assertEquals(Duration.ofSeconds(4), p.nextDelay(2));
        assertEquals(Duration.ofSeconds(8), p.nextDelay(3));
    }

    @Test
    void backoffIsCappedAtMaxInterval() {
        RetryPolicy p = RetryPolicy.newBuilder()
                .setInitialInterval(Duration.ofSeconds(1))
                .setBackoffCoefficient(10.0)
                .setMaxInterval(Duration.ofSeconds(5))
                .build();

        assertEquals(Duration.ofSeconds(5), p.nextDelay(10));
    }

    @Test
    void noRetryListBlocksMatchingTypesAndSubtypes() {
        RetryPolicy p = RetryPolicy.newBuilder()
                .addNoRetry(RuntimeException.class)
                .build();

        assertTrue(p.isNoRetry(new IllegalArgumentException("sub")));
        assertFalse(p.isRetryable(new IllegalArgumentException("sub")));
        assertTrue(p.isRetryable(new Exception("not a runtime ex")));
    }

    @Test
    void whitelistRestrictsRetriesToListedTypes() {
        RetryPolicy p = RetryPolicy.newBuilder()
                .addRetryOn(IllegalStateException.class)
                .build();

        assertTrue(p.isRetryable(new IllegalStateException("listed")));
        assertFalse(p.isRetryable(new RuntimeException("not listed")));
    }

    @Test
    void emptyPolicyRetriesEverythingByDefault() {
        RetryPolicy p = RetryPolicy.defaultPolicy();
        assertTrue(p.isRetryable(new RuntimeException("anything")));
    }
}
