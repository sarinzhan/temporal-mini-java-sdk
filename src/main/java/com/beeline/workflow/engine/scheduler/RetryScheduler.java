package com.beeline.workflow.engine.scheduler;

public interface RetryScheduler {
    void pollDueRetries();
}
