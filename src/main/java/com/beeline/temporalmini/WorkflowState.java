package com.beeline.temporalmini;

/**
 * Persisted workflow lifecycle states.
 *
 * <p>Note: there is no {@code RUNNING} value here on purpose. "Running" — meaning the
 * engine is actively executing the workflow at this moment — is tracked in memory by
 * {@link WorkflowRuntimeRegistry}, not in the database. {@code RUNNABLE} covers both
 * "queued for next run" and "currently being executed". The runtime registry is the
 * source of truth for the latter and is exposed through the UI as a separate badge.
 */
public enum WorkflowState {
    NEW, RUNNABLE, BLOCKED, FINISHED, FAILED
}
