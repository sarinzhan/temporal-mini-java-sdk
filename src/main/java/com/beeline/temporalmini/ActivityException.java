package com.beeline.temporalmini;

public class ActivityException extends RuntimeException {

    private final String activityName;

    public ActivityException(String activityName, Throwable cause) {
        super("Activity '" + activityName + "' failed: " + cause.getMessage(), cause);
        this.activityName = activityName;
    }

    public String getActivityName() {
        return activityName;
    }
}
