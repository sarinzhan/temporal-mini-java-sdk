package com.beeline.workflow.core.config;

import java.time.Duration;

public final class ActivityOptions {

    private final Duration startToCloseTimeout;
    private final RetryPolicy retryPolicy;
    private final String idempotencyKey;
    private final String signalName;

    private ActivityOptions(Builder b) {
        this.startToCloseTimeout = b.startToCloseTimeout;
        this.retryPolicy = b.retryPolicy;
        this.idempotencyKey = b.idempotencyKey;
        this.signalName = b.signalName;
    }

    public Duration getStartToCloseTimeout() { return startToCloseTimeout; }
    public RetryPolicy getRetryPolicy() { return retryPolicy; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public String getSignalName() { return signalName; }

    public static ActivityOptions defaultOptions() {
        return newBuilder().build();
    }

    public static Builder newBuilder() {
        return new Builder();
    }

    public static final class Builder {
        private Duration startToCloseTimeout = Duration.ofMinutes(1);
        private RetryPolicy retryPolicy = RetryPolicy.defaultPolicy();
        private String idempotencyKey;
        private String signalName;

        public Builder setStartToCloseTimeout(Duration v) { this.startToCloseTimeout = v; return this; }
        public Builder setRetryPolicy(RetryPolicy v) { this.retryPolicy = v; return this; }
        public Builder setIdempotencyKey(String v) { this.idempotencyKey = v; return this; }
        public Builder setSignalName(String v) { this.signalName = v; return this; }

        public ActivityOptions build() { return new ActivityOptions(this); }
    }
}
