package com.beeline.workflow.core.exception;

import java.time.Duration;

public class ActivityTimeoutException extends WorkflowException {
    private final String activityName;
    private final Duration timeout;

    public ActivityTimeoutException(String activityName, Duration timeout) {
        super("Activity '" + activityName + "' timed out after " + timeout);
        this.activityName = activityName;
        this.timeout = timeout;
    }

    public String getActivityName() { return activityName; }
    public Duration getTimeout() { return timeout; }
}
