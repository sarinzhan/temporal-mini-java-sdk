package com.beeline.workflow.core.config;

import java.time.Duration;

public final class ActivityOptions {

    private final Duration startToCloseTimeout;
    private final RetryPolicy retryPolicy;

    private ActivityOptions(Builder b) {
        this.startToCloseTimeout = b.startToCloseTimeout;
        this.retryPolicy = b.retryPolicy;
    }

    public Duration getStartToCloseTimeout() { return startToCloseTimeout; }
    public RetryPolicy getRetryPolicy() { return retryPolicy; }

    public static ActivityOptions defaultOptions() {
        return newBuilder().build();
    }

    public static Builder newBuilder() {
        return new Builder();
    }

    public static final class Builder {
        private Duration startToCloseTimeout = Duration.ofMinutes(1);
        private RetryPolicy retryPolicy = RetryPolicy.defaultPolicy();

        public Builder setStartToCloseTimeout(Duration v) { this.startToCloseTimeout = v; return this; }
        public Builder setRetryPolicy(RetryPolicy v) { this.retryPolicy = v; return this; }

        public ActivityOptions build() { return new ActivityOptions(this); }
    }
}
