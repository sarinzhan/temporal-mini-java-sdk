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
 * the workflow is "waiting retry" (UI: WAITING); once the time passes it is "ready to run"
 * (UI: IN_QUEUE) and will be submitted on the next poll.
 *
 * <p>"Currently executing" is not a persisted state — {@link WorkflowRuntimeRegistry}
 * tracks in-flight execution in memory and surfaces it as the UI's "RUNNING" virtual
 * state and the {@code runtimeCount} metric. The registry distinguishes
 * {@code SUBMITTED} (handed to the executor, still in its queue) from {@code RUNNING}
 * (worker thread actually executing); only the latter is counted as running.
 *
 * <p>{@code STOPPED} replaced the historical {@code BLOCKED} value at the API/UI level.
 * The database column still stores {@code "BLOCKED"} — see {@link WorkflowStateConverter}.
 */
public enum WorkflowState {
    NEW, RETRY, STOPPED, FINISHED, FAILED
}
