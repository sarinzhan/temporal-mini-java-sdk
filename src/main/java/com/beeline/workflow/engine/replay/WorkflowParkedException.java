package com.beeline.workflow.engine.replay;

/**
 * Thrown to signal "this turn is over, please commit and stop". Not an error — the
 * {@link com.beeline.workflow.engine.executor.WorkflowExecutor} catches it as a normal end
 * of a decision turn.
 *
 * <p>The only suspending command now is an activity retry: when a failed-but-retryable activity
 * schedules its next attempt, it writes {@code ACTIVITY_RETRY_SCHEDULED} + a {@code wflow.schedule}
 * row (with the backoff {@code fireAt}) and parks. The {@code WakeupScheduler} re-enqueues the
 * workflow when the schedule row is due, and replay resumes at the retrying activity.
 */
public class WorkflowParkedException extends RuntimeException {

    private final int seq;

    public WorkflowParkedException(int seq) {
        super("workflow parked: activity retry seq=" + seq);
        this.seq = seq;
    }

    public int getSeq() { return seq; }
}
