package com.beeline.workflow.engine.retry;

import com.beeline.workflow.core.config.RetryPolicy;
import com.beeline.workflow.core.exception.NonRetryableException;

import java.time.Duration;
import java.time.Instant;

/**
 * Pure decision: given the cause of an attempt failure, the current attempt number and the policy,
 * decide whether to schedule another attempt and when. Replaces the inline branch from
 * {@code ActivityExecutorImpl.failOrRetry}.
 */
public final class RetryDecider {

    public RetryDecision decide(Throwable cause, int attempt, RetryPolicy policy) {
        if (cause instanceof NonRetryableException) {
            return new RetryDecision.Terminal(true);
        }
        if (!policy.isRetryable(cause) || attempt >= policy.getMaxAttempts()) {
            return new RetryDecision.Terminal(false);
        }
        return new RetryDecision.Retry(policy.nextDelay(attempt));
    }

    public Instant computeFireAt(Duration delay) {
        return Instant.now().plus(delay);
    }
}
