package com.beeline.workflow.engine.turn;

/**
 * Result of a single workflow decision turn. Returned by {@link WorkflowTurnRunner} so the
 * worker loop knows how to finalize the task row (DONE / DEAD / leave alone).
 */
public enum Outcome {
    COMPLETED,
    /** Parked waiting out an activity retry backoff; the WakeupScheduler will re-enqueue. */
    PARKED,
    FAILED,
    UNKNOWN,
    /** Lease was lost mid-turn; writes were discarded and the new owner takes over. */
    LOST
}
