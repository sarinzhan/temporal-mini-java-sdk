package com.beeline.workflow.core.api;

/**
 * Read-only context of the activity currently executing, available inside an activity body via
 * {@link Workflow#currentActivity()} / {@link Workflow#currentActivityKey()}.
 *
 * <p>Activities are <b>at-least-once</b>: a body may run more than once (retry, replay after a crash
 * in the "executed but not yet recorded" window, or a rare concurrent duplicate in a cluster). To get
 * <b>effectively-once</b>, pass {@link #idempotencyKey()} to the external system and let it
 * deduplicate. The key is derived from the workflow id and the deterministic command {@code seq}, so
 * it is identical on every retry and every replay of the same logical activity.
 */
public record ActivityExecution(long workflowId, int seq, int attempt) {

    /**
     * Stable idempotency key for this activity: {@code wf:<workflowId>:<seq>}. Deliberately excludes
     * {@link #attempt()} — every retry of the same activity must present the SAME key so the
     * downstream system recognises and drops the duplicate.
     */
    public String idempotencyKey() {
        return "wf:" + workflowId + ":" + seq;
    }
}
