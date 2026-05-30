package com.beeline.workflow.engine.turn;

import com.beeline.workflow.core.model.Event;
import com.beeline.workflow.core.model.Task;
import com.beeline.workflow.core.model.TaskStatus;
import com.beeline.workflow.core.model.WorkflowInstance;
import com.beeline.workflow.engine.codec.PayloadCodec;
import com.beeline.workflow.engine.command.CommandContext;
import com.beeline.workflow.engine.command.CommandDispatcher;
import com.beeline.workflow.engine.context.WorkflowContextHolder;
import com.beeline.workflow.engine.lifecycle.TurnResult;
import com.beeline.workflow.engine.lifecycle.WorkflowOutcomeMapper;
import com.beeline.workflow.engine.replay.EventLogImpl;
import com.beeline.workflow.engine.replay.EventLogFactory;
import com.beeline.workflow.engine.replay.HistoryCursor;
import com.beeline.workflow.engine.replay.LockLostException;
import com.beeline.workflow.engine.replay.ReplayState;
import com.beeline.workflow.engine.replay.ReplayStateImpl;
import com.beeline.workflow.engine.replay.TaskLease;
import com.beeline.workflow.engine.turn.TurnCommitter.WorkflowMutation;
import com.beeline.workflow.persistence.repository.EventRepository;
import com.beeline.workflow.persistence.repository.WorkflowRepository;
import com.beeline.workflow.registry.WorkflowRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.List;

/**
 * Drives one workflow decision turn. The entry method (and its inline activities) run with NO open
 * DB transaction — all writes are buffered in the {@link EventLogImpl}. When the turn ends (normal
 * completion, terminal failure, or a retry park), the whole buffer plus the workflow status and the
 * task finalization are flushed atomically by {@link TurnCommitter}. A crash before that commit
 * leaves no partial history; the lease expires and the turn replays cleanly.
 */
public final class WorkflowTurnRunner {

    private static final Logger log = LoggerFactory.getLogger(WorkflowTurnRunner.class);

    private final WorkflowRegistry workflowRegistry;
    private final WorkflowRepository workflowRepository;
    private final EventRepository eventRepository;
    private final ObjectMapper objectMapper;
    private final PayloadCodec codec;
    private final EventLogFactory eventLogFactory;
    private final WorkflowOutcomeMapper outcomeMapper;
    private final CommandDispatcher dispatcher;
    private final TurnCommitter committer;

    public WorkflowTurnRunner(WorkflowRegistry workflowRegistry,
                              WorkflowRepository workflowRepository,
                              EventRepository eventRepository,
                              ObjectMapper objectMapper,
                              PayloadCodec codec,
                              EventLogFactory eventLogFactory,
                              WorkflowOutcomeMapper outcomeMapper,
                              CommandDispatcher dispatcher,
                              TurnCommitter committer) {
        this.workflowRegistry = workflowRegistry;
        this.workflowRepository = workflowRepository;
        this.eventRepository = eventRepository;
        this.objectMapper = objectMapper;
        this.codec = codec;
        this.eventLogFactory = eventLogFactory;
        this.outcomeMapper = outcomeMapper;
        this.dispatcher = dispatcher;
        this.committer = committer;
    }

    public Outcome run(Task task, TaskLease lease) {
        WorkflowInstance wf = workflowRepository.findById(task.getWorkflowId()).orElse(null);
        if (wf == null) {
            log.warn("Task {} references missing workflow {} — discarding", task.getId(), task.getWorkflowId());
            return Outcome.UNKNOWN;
        }

        Object bean = workflowRegistry.createInstance(wf.getWorkflowType());
        if (bean == null) {
            return failFast(wf, task, lease, "Unknown workflow type: " + wf.getWorkflowType());
        }

        Method entryMethod = workflowRegistry.getEntryMethod(wf.getWorkflowType());
        if (entryMethod == null) {
            return failFast(wf, task, lease, "No @WorkflowMethod registered for type: " + wf.getWorkflowType());
        }

        Object[] callArgs;
        try {
            callArgs = buildCallArgs(entryMethod, wf.getInput());
        } catch (Exception ex) {
            return failFast(wf, task, lease, "Failed to deserialize workflow input: " + ex.getMessage());
        }

        // Load history *before* writing TASK_STARTED so the cursor only sees prior turns.
        List<Event> history = eventRepository.findByWorkflowIdOrderByIdAsc(wf.getId());
        HistoryCursor cursor = new HistoryCursor(wf.getId(), history, objectMapper);
        ReplayState replayState = new ReplayStateImpl(cursor, codec);
        EventLogImpl eventLog = eventLogFactory.create(wf.getId(), lease);

        eventLog.workflowTaskStarted();

        CommandContext ctx = new CommandContext(wf.getId(), task.getId(),
                replayState, eventLog, lease, codec, dispatcher);
        WorkflowContextHolder.set(ctx);

        TurnResult result;
        try {
            Object value = entryMethod.invoke(bean, callArgs);
            result = outcomeMapper.onCompleted(wf, value, eventLog);
        } catch (LockLostException lost) {
            log.warn("[{}] lock lost during turn — discarding writes, another node owns the task", wf.getId());
            return Outcome.LOST;
        } catch (Throwable thrown) {
            try {
                result = outcomeMapper.onThrown(wf, thrown, eventLog);
            } catch (LockLostException lost) {
                log.warn("[{}] lock lost during turn — discarding writes, another node owns the task", wf.getId());
                return Outcome.LOST;
            }
        } finally {
            WorkflowContextHolder.clear();
        }

        boolean committed = committer.commit(wf.getId(), lease, eventLog, result.mutation(), result.taskStatus());
        if (!committed) {
            return Outcome.LOST;
        }
        return result.outcome();
    }

    /**
     * Engine-level failure before any workflow code runs (unknown type, bad input). Buffers a
     * WORKFLOW_FAILED + WORKFLOW_TASK_FAILED and commits atomically with the task marked DEAD.
     */
    private Outcome failFast(WorkflowInstance wf, Task task, TaskLease lease, String error) {
        EventLogImpl eventLog = eventLogFactory.create(wf.getId(), lease);
        try {
            eventLog.workflowTaskStarted();
            eventLog.workflowFailed(error);
            eventLog.workflowTaskFailed(error);
        } catch (LockLostException lost) {
            return Outcome.LOST;
        }
        boolean committed = committer.commit(wf.getId(), lease, eventLog,
                WorkflowMutation.failed(error), TaskStatus.DEAD);
        return committed ? Outcome.FAILED : Outcome.LOST;
    }

    private Object[] buildCallArgs(Method entryMethod, String inputJson) {
        int count = entryMethod.getParameterCount();
        if (count == 0) return new Object[0];
        if (count == 1) {
            Type paramType = entryMethod.getGenericParameterTypes()[0];
            Object value = (inputJson == null) ? null : codec.decodeWorkflowValue(inputJson, paramType);
            return new Object[]{value};
        }
        throw new IllegalStateException(
                "Workflow entry method must take 0 or 1 parameters: " + entryMethod);
    }
}
