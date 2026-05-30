package com.beeline.workflow.engine.retry;

import java.time.Duration;

public sealed interface RetryDecision {

    record Retry(Duration delay) implements RetryDecision {}

    /** Terminally fail. {@code nonRetryable} distinguishes policy-blocked vs budget-exhausted. */
    record Terminal(boolean nonRetryable) implements RetryDecision {}
}
