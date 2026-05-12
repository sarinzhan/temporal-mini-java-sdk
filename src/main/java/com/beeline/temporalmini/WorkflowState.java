package com.beeline.temporalmini;

/**
 * Persisted workflow lifecycle states.
 *
 * <pre>
 *  NEW ──► (scheduler picks up) ──► FINISHED
 *                                 ──► RETRY ──► (next attempt) ──► FINISHED
 *                                 │             └──────────────────► FAILED
 *                                 └──────────────────────────────── STOPPED
 * </pre>
 *
 * <p>{@code NEW} — workflow was just created, has never been executed.
 * <p>{@code RETRY} — a previous attempt failed with a retriable error; will be picked up
 * again when {@code nextRetryAt <= now}. While {@code nextRetryAt} is still in the future
 * the workflow is "waiting"; once the time passes it is "queued" for the next run.
 * "Currently executing" is not a persisted state — {@link WorkflowRuntimeRegistry} tracks
 * in-flight execution in memory and exposes it as the {@code running} metric.
 *
 * <p>{@code STOPPED} replaced the historical {@code BLOCKED} value at the API/UI level.
 * The database column still stores {@code "BLOCKED"} — see {@link WorkflowStateConverter}.
 */
public enum WorkflowState {
    NEW, RETRY, STOPPED, FINISHED, FAILED
}
