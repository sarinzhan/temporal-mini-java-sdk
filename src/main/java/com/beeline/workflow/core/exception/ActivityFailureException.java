package com.beeline.workflow.core.exception;

public class ActivityFailureException extends WorkflowException {
    private final String activityName;
    private final int attempt;

    public ActivityFailureException(String activityName, int attempt, String message, Throwable cause) {
        super("Activity '" + activityName + "' failed on attempt " + attempt + ": " + message, cause);
        this.activityName = activityName;
        this.attempt = attempt;
    }

    public String getActivityName() { return activityName; }
    public int getAttempt() { return attempt; }
}
