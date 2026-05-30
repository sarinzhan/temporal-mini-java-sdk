package com.beeline.workflow.engine.replay;

import java.time.Instant;

/**
 * Single fenced-write API for the engine. Replaces both {@code EventSink} and the per-class
 * {@code record*}/{@code saveEvent} helpers that used to live in {@code ActivityExecutorImpl} and
 * {@code WorkflowExecutor}. Each instance is bound to a single (workflowId, taskLease) pair —
 * every write asserts lease ownership and runs in its own transaction.
 */
public interface EventLog {

    // ── Activity ────────────────────────────────────────────────────────────

    void activityStarted(int seq, String name, int attempt);

    void activityCompleted(int seq, String name, int attempt, Object result);

    void activityFailed(int seq, String name, int attempt, String reason);

    /** Atomically writes ACTIVITY_RETRY_SCHEDULED + the wflow.schedule row in one transaction. */
    void activityRetryScheduled(int seq, String name, int attempt, Instant fireAt, String reason);

    // ── SideEffect / Version ────────────────────────────────────────────────

    void sideEffectRecorded(int seq, Object result);

    void versionMarker(int seq, String changeId, int version);

    // ── Workflow lifecycle (no seq) ─────────────────────────────────────────

    void workflowTaskStarted();

    void workflowTaskCompleted();

    void workflowCompleted(String resultJson);

    void workflowFailed(String reason);

    void workflowCreated(String inputJson);

    void workflowTaskQueued(String reason);
}
