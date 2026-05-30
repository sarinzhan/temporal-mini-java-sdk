package com.beeline.workflow.it.support;

/**
 * Instruction set passed as the workflow input. Tells {@link ScenarioWorkflowImpl} which activity
 * behaviour to drive and how the retry policy / timeout should be configured, so a single workflow
 * type can exercise every success and failure flow without a bean per case.
 *
 * @param key        unique per test run; namespaces the activity invocation counters
 * @param type       which flow to run (see ScenarioWorkflowImpl)
 * @param failTimes  for "flaky"/"replay"/"sideEffect": how many leading attempts throw
 * @param sleepMs    for "timeout": how long the first attempt sleeps
 * @param maxAttempts retry budget for the activity
 * @param timeoutMs  start-to-close timeout for the activity (0 = default 30s)
 */
public record Scenario(
        String key,
        String type,
        int failTimes,
        long sleepMs,
        int maxAttempts,
        long timeoutMs) {

    public static Scenario of(String key, String type) {
        return new Scenario(key, type, 0, 0, 3, 0);
    }

    public Scenario withFailTimes(int v) {
        return new Scenario(key, type, v, sleepMs, maxAttempts, timeoutMs);
    }

    public Scenario withSleepMs(long v) {
        return new Scenario(key, type, failTimes, v, maxAttempts, timeoutMs);
    }

    public Scenario withMaxAttempts(int v) {
        return new Scenario(key, type, failTimes, sleepMs, v, timeoutMs);
    }

    public Scenario withTimeoutMs(long v) {
        return new Scenario(key, type, failTimes, sleepMs, maxAttempts, v);
    }
}
