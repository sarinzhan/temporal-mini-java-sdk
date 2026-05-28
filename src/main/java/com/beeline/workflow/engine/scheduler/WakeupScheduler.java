package com.beeline.workflow.engine.scheduler;

/**
 * Periodically polls the {@code wflow.schedule} table for due rows (e.g. an activity retry whose
 * backoff has elapsed) and enqueues a {@code workflow} task so a worker re-runs the parked workflow.
 */
public interface WakeupScheduler {

    void pollAndFire();
}
