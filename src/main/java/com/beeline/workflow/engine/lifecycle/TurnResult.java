package com.beeline.workflow.engine.lifecycle;

import com.beeline.workflow.core.model.TaskStatus;
import com.beeline.workflow.engine.turn.Outcome;
import com.beeline.workflow.engine.turn.TurnCommitter.WorkflowMutation;

/**
 * What one decision turn decided, ready to be committed atomically. Pairs the {@link Outcome}
 * the worker loop reacts to with the workflow-row {@link WorkflowMutation} and the final task
 * status. Lifecycle events themselves are already buffered in the EventLog by the time this is
 * produced.
 */
public record TurnResult(Outcome outcome, WorkflowMutation mutation, TaskStatus taskStatus) {

    public static TurnResult completed(String resultJson) {
        return new TurnResult(Outcome.COMPLETED, WorkflowMutation.completed(resultJson), TaskStatus.DONE);
    }

    public static TurnResult failed(String error) {
        return new TurnResult(Outcome.FAILED, WorkflowMutation.failed(error), TaskStatus.DEAD);
    }

    /** Parked on an activity retry: workflow stays RUNNING, task is DONE (a fresh wakeup task drives the next turn). */
    public static TurnResult parked() {
        return new TurnResult(Outcome.PARKED, WorkflowMutation.running(), TaskStatus.DONE);
    }
}
