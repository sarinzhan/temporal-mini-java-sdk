package com.beeline.workflow.engine.replay;

/**
 * Thrown by {@code Workflow.sleep}, {@code Workflow.await}, and other suspending commands
 * to signal "this turn is over, please commit and re-schedule a wake-up". Not an error —
 * the {@link com.beeline.workflow.engine.executor.WorkflowExecutor} catches it as a normal
 * termination of a decision turn.
 */
public class WorkflowParkedException extends RuntimeException {

    public enum Kind { TIMER, AWAIT }

    private final Kind kind;
    private final int seq;

    public WorkflowParkedException(Kind kind, int seq) {
        super("workflow parked: " + kind + " seq=" + seq);
        this.kind = kind;
        this.seq = seq;
    }

    public Kind getKind() { return kind; }
    public int getSeq() { return seq; }
}
