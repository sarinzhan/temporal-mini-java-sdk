package com.beeline.workflow.core.config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class RetryPolicy {

    private final int maxAttempts;
    private final Duration initialInterval;
    private final double backoffCoefficient;
    private final List<Class<? extends Throwable>> noRetryOn;

    private RetryPolicy(Builder b) {
        this.maxAttempts = b.maxAttempts;
        this.initialInterval = b.initialInterval;
        this.backoffCoefficient = b.backoffCoefficient;
        this.noRetryOn = Collections.unmodifiableList(new ArrayList<>(b.noRetryOn));
    }

    public int getMaxAttempts() { return maxAttempts; }
    public Duration getInitialInterval() { return initialInterval; }
    public double getBackoffCoefficient() { return backoffCoefficient; }
    public List<Class<? extends Throwable>> getNoRetryOn() { return noRetryOn; }

    public boolean isNoRetry(Throwable ex) {
        for (Class<? extends Throwable> c : noRetryOn) {
            if (c.isInstance(ex)) return true;
        }
        return false;
    }

    public Duration nextDelay(int attempt) {
        double mult = Math.pow(backoffCoefficient, Math.max(0, attempt));
        return Duration.ofMillis((long) (initialInterval.toMillis() * mult));
    }

    public static RetryPolicy defaultPolicy() {
        return newBuilder().build();
    }

    public static Builder newBuilder() {
        return new Builder();
    }

    public static final class Builder {
        private int maxAttempts = 3;
        private Duration initialInterval = Duration.ofSeconds(1);
        private double backoffCoefficient = 2.0;
        private final List<Class<? extends Throwable>> noRetryOn = new ArrayList<>();

        public Builder setMaxAttempts(int v) { this.maxAttempts = v; return this; }
        public Builder setInitialInterval(Duration v) { this.initialInterval = v; return this; }
        public Builder setBackoffCoefficient(double v) { this.backoffCoefficient = v; return this; }
        public Builder addNoRetry(Class<? extends Throwable> c) { this.noRetryOn.add(c); return this; }

        public RetryPolicy build() { return new RetryPolicy(this); }
    }
}
