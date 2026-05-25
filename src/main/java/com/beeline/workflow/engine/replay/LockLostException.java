package com.beeline.workflow.engine.replay;

/**
 * Thrown when a worker tries to commit a write for a task whose lock it no longer holds —
 * i.e. the lease expired and another node reclaimed the task. The current turn must be
 * discarded without persisting anything; the new owner is now responsible for the workflow.
 */
public final class LockLostException extends RuntimeException {

    private final long taskId;

    public LockLostException(long taskId, String token) {
        super("Lock lost for task " + taskId + " (token=" + token + ") — another node owns it now");
        this.taskId = taskId;
    }

    public long getTaskId() {
        return taskId;
    }
}
