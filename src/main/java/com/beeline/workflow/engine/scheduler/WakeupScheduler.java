package com.beeline.workflow.engine.scheduler;

/**
 * Periodically polls expired timers, expired awaits, and due retries; writes the
 * corresponding _FIRED events and enqueues workflow tasks so a worker can resume the workflow.
 */
public interface WakeupScheduler {

    void pollAndFire();
}
