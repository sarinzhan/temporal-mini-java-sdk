package com.beeline.workflow.engine.lifecycle;

import com.beeline.workflow.core.exception.ActivityFailureException;
import com.beeline.workflow.core.exception.ActivityTimeoutException;
import com.beeline.workflow.core.model.WorkflowInstance;
import com.beeline.workflow.engine.codec.PayloadCodec;
import com.beeline.workflow.engine.replay.EventLog;
import com.beeline.workflow.engine.replay.LockLostException;
import com.beeline.workflow.engine.replay.NonDeterminismException;
import com.beeline.workflow.engine.replay.WorkflowParkedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.InvocationTargetException;

/**
 * Maps the exception that ended a workflow turn (or {@code null} for normal completion) onto a
 * {@link TurnResult} — the outcome + the workflow-row mutation + the final task status — while
 * buffering the corresponding lifecycle events through {@link EventLog}. Nothing is persisted here;
 * the {@code WorkflowTurnRunner} commits the whole turn atomically afterwards.
 */
public final class WorkflowOutcomeMapper {

    private static final Logger log = LoggerFactory.getLogger(WorkflowOutcomeMapper.class);

    private final PayloadCodec codec;

    public WorkflowOutcomeMapper(PayloadCodec codec) {
        this.codec = codec;
    }

    /** Called on normal completion of the entry method. */
    public TurnResult onCompleted(WorkflowInstance wf, Object result, EventLog eventLog) {
        String resultJson = codec.encodeWorkflowValue(result);
        eventLog.workflowCompleted(resultJson);
        eventLog.workflowTaskCompleted();
        return TurnResult.completed(resultJson);
    }

    /**
     * Called when the entry method threw. {@code thrown} is the exception that propagated out of
     * {@code Method.invoke}; the mapper unwraps {@link InvocationTargetException} once. Propagates
     * {@link LockLostException} so the turn runner returns {@code Outcome.LOST} without committing.
     */
    public TurnResult onThrown(WorkflowInstance wf, Throwable thrown, EventLog eventLog) {
        Throwable cause = unwrap(thrown);
        if (cause instanceof LockLostException lost) {
            throw lost;
        }
        if (cause instanceof WorkflowParkedException parked) {
            log.info("[{}] workflow parked: activity retry seq={}", wf.getId(), parked.getSeq());
            eventLog.workflowTaskCompleted();
            return TurnResult.parked();
        }
        if (cause instanceof ActivityTimeoutException ate) {
            log.warn("[{}] workflow failed — activity timed out", wf.getId());
            String msg = safeMessage(ate);
            eventLog.workflowFailed(msg);
            eventLog.workflowTaskFailed(msg);
            return TurnResult.failed(msg);
        }
        if (cause instanceof ActivityFailureException afe) {
            log.warn("[{}] workflow failed — activity {} terminally failed", wf.getId(), afe.getActivityName());
            String msg = safeMessage(afe);
            eventLog.workflowFailed(msg);
            eventLog.workflowTaskFailed(msg);
            return TurnResult.failed(msg);
        }
        if (cause instanceof NonDeterminismException nde) {
            log.error("[{}] workflow non-determinism detected: {}", wf.getId(), nde.getMessage());
            String msg = "Non-determinism: " + nde.getMessage();
            eventLog.workflowFailed(nde.getMessage());
            eventLog.workflowTaskFailed(msg);
            return TurnResult.failed(msg);
        }
        String msg = safeMessage(cause);
        eventLog.workflowFailed(msg);
        eventLog.workflowTaskFailed(msg);
        return TurnResult.failed(msg);
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
