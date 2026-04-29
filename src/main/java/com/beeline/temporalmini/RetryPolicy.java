package com.beeline.temporalmini;

public class RetryPolicy {

    private final int maxAttempts;
    private final boolean exponentialBackoff;
    private final long retryIntervalMs;

    public static final RetryPolicy DEFAULT = exponential(3, 1_000);

    private RetryPolicy(int maxAttempts, boolean exponentialBackoff, long retryIntervalMs) {
        this.maxAttempts = maxAttempts;
        this.exponentialBackoff = exponentialBackoff;
        this.retryIntervalMs = retryIntervalMs;
    }

    public static RetryPolicy noRetry() {
        return new RetryPolicy(1, false, 0);
    }

    public static RetryPolicy fixed(int maxAttempts, long retryIntervalMs) {
        return new RetryPolicy(maxAttempts, false, retryIntervalMs);
    }

    public static RetryPolicy exponential(int maxAttempts, long baseIntervalMs) {
        return new RetryPolicy(maxAttempts, true, baseIntervalMs);
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public boolean isExponentialBackoff() {
        return exponentialBackoff;
    }

    public long getRetryIntervalMs() {
        return retryIntervalMs;
    }

    public long delayMs(int attempt) {
        if (exponentialBackoff) {
            return (long) Math.pow(2, attempt) * retryIntervalMs;
        }
        return retryIntervalMs;
    }
}
