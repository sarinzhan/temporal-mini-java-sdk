package com.beeline.workflow.engine.lifecycle;

import com.beeline.workflow.core.exception.ActivityFailureException;
import com.beeline.workflow.core.model.WorkflowInstance;
import com.beeline.workflow.engine.codec.PayloadCodec;
import com.beeline.workflow.engine.replay.EventLog;
import com.beeline.workflow.engine.replay.LockLostException;
import com.beeline.workflow.engine.replay.NonDeterminismException;
import com.beeline.workflow.engine.replay.TaskLease;
import com.beeline.workflow.engine.replay.WorkflowParkedException;
import com.beeline.workflow.engine.turn.Outcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.InvocationTargetException;

/**
 * Maps the exception that ended a workflow turn (or {@code null} for normal completion) onto the
 * {@link Outcome} the turn runner returns to the worker loop, while writing the corresponding
 * workflow-lifecycle events through {@link EventLog} and updating the workflow row via
 * {@link WorkflowLifecycleWriter}. Centralizes what used to be the big switch in
 * {@code WorkflowExecutor.runEntry}.
 */
public final class WorkflowOutcomeMapper {

    private static final Logger log = LoggerFactory.getLogger(WorkflowOutcomeMapper.class);

    private final WorkflowLifecycleWriter lifecycle;
    private final PayloadCodec codec;

    public WorkflowOutcomeMapper(WorkflowLifecycleWriter lifecycle, PayloadCodec codec) {
        this.lifecycle = lifecycle;
        this.codec = codec;
    }

    /** Called on normal completion of the entry method. */
    public Outcome onCompleted(WorkflowInstance wf, Object result, EventLog eventLog, TaskLease lease) {
        lifecycle.markCompleted(wf, result, lease);
        eventLog.workflowCompleted(codec.encodeWorkflowValue(result));
        eventLog.workflowTaskCompleted();
        return Outcome.COMPLETED;
    }

    /**
     * Called when the entry method threw. {@code thrown} is the exception that propagated out
     * of {@code Method.invoke}; the mapper unwraps {@link InvocationTargetException} once.
     * Propagates {@link LockLostException} so the turn runner returns {@link Outcome#LOST}
     * without writing anything.
     */
    public Outcome onThrown(WorkflowInstance wf, Throwable thrown, EventLog eventLog, TaskLease lease) {
        Throwable cause = unwrap(thrown);
        if (cause instanceof LockLostException lost) {
            throw lost;
        }
        if (cause instanceof WorkflowParkedException parked) {
            log.info("[{}] workflow parked: activity retry seq={}", wf.getId(), parked.getSeq());
            eventLog.workflowTaskCompleted();
            return Outcome.PARKED;
        }
        if (cause instanceof ActivityFailureException afe) {
            log.warn("[{}] workflow failed — activity {} terminally failed", wf.getId(), afe.getActivityName());
            String msg = safeMessage(afe);
            lifecycle.markFailed(wf, msg, lease);
            eventLog.workflowFailed(msg);
            eventLog.workflowTaskCompleted();
            return Outcome.FAILED;
        }
        if (cause instanceof NonDeterminismException nde) {
            log.error("[{}] workflow non-determinism detected: {}", wf.getId(), nde.getMessage());
            String msg = "Non-determinism: " + nde.getMessage();
            lifecycle.markFailed(wf, msg, lease);
            eventLog.workflowFailed(nde.getMessage());
            eventLog.workflowTaskCompleted();
            return Outcome.FAILED;
        }
        String msg = safeMessage(cause);
        lifecycle.markFailed(wf, msg, lease);
        eventLog.workflowFailed(msg);
        eventLog.workflowTaskCompleted();
        return Outcome.FAILED;
    }

    private static Throwable unwrap(Throwable t) {
        if (t instanceof InvocationTargetException ite) {
            return ite.getCause() != null ? ite.getCause() : ite;
        }
        return t;
    }

    private static String safeMessage(Throwable t) {
        return t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
    }
}
