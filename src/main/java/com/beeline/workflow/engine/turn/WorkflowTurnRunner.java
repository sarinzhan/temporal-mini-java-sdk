package com.beeline.workflow.engine.turn;

import com.beeline.workflow.core.model.Event;
import com.beeline.workflow.core.model.Task;
import com.beeline.workflow.core.model.WorkflowInstance;
import com.beeline.workflow.engine.codec.PayloadCodec;
import com.beeline.workflow.engine.command.CommandContext;
import com.beeline.workflow.engine.command.CommandDispatcher;
import com.beeline.workflow.engine.context.WorkflowContextHolder;
import com.beeline.workflow.engine.lifecycle.WorkflowLifecycleWriter;
import com.beeline.workflow.engine.lifecycle.WorkflowOutcomeMapper;
import com.beeline.workflow.engine.replay.EventLog;
import com.beeline.workflow.engine.replay.EventLogFactory;
import com.beeline.workflow.engine.replay.HistoryCursor;
import com.beeline.workflow.engine.replay.LockLostException;
import com.beeline.workflow.engine.replay.ReplayState;
import com.beeline.workflow.engine.replay.ReplayStateImpl;
import com.beeline.workflow.engine.replay.TaskLease;
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
 * Drives one workflow decision turn. Thin orchestration: load the workflow, build the per-turn
 * scaffolding ({@link ReplayState}, {@link EventLog}, {@link CommandContext}), invoke the entry
 * method, and let {@link WorkflowOutcomeMapper} turn the result/exception into an {@link Outcome}.
 */
public final class WorkflowTurnRunner {

    private static final Logger log = LoggerFactory.getLogger(WorkflowTurnRunner.class);

    private final WorkflowRegistry workflowRegistry;
    private final WorkflowRepository workflowRepository;
    private final EventRepository eventRepository;
    private final ObjectMapper objectMapper;
    private final PayloadCodec codec;
    private final EventLogFactory eventLogFactory;
    private final WorkflowLifecycleWriter lifecycle;
    private final WorkflowOutcomeMapper outcomeMapper;
    private final CommandDispatcher dispatcher;

    public WorkflowTurnRunner(WorkflowRegistry workflowRegistry,
                              WorkflowRepository workflowRepository,
                              EventRepository eventRepository,
                              ObjectMapper objectMapper,
                              PayloadCodec codec,
                              EventLogFactory eventLogFactory,
                              WorkflowLifecycleWriter lifecycle,
                              WorkflowOutcomeMapper outcomeMapper,
                              CommandDispatcher dispatcher) {
        this.workflowRegistry = workflowRegistry;
        this.workflowRepository = workflowRepository;
        this.eventRepository = eventRepository;
        this.objectMapper = objectMapper;
        this.codec = codec;
        this.eventLogFactory = eventLogFactory;
        this.lifecycle = lifecycle;
        this.outcomeMapper = outcomeMapper;
        this.dispatcher = dispatcher;
    }

    public Outcome run(Task task, TaskLease lease) {
        WorkflowInstance wf = workflowRepository.findById(task.getWorkflowId()).orElse(null);
        if (wf == null) {
            log.warn("Task {} references missing workflow {} — discarding", task.getId(), task.getWorkflowId());
            return Outcome.UNKNOWN;
        }

        Object bean = workflowRegistry.createInstance(wf.getWorkflowType());
        if (bean == null) {
            lifecycle.markFailed(wf, "Unknown workflow type: " + wf.getWorkflowType(), lease);
            return Outcome.FAILED;
        }

        Method entryMethod = workflowRegistry.getEntryMethod(wf.getWorkflowType());
        if (entryMethod == null) {
            lifecycle.markFailed(wf, "No @WorkflowMethod registered for type: " + wf.getWorkflowType(), lease);
            return Outcome.FAILED;
        }

        Object[] callArgs;
        try {
            callArgs = buildCallArgs(entryMethod, wf.getInput());
        } catch (Exception ex) {
            lifecycle.markFailed(wf, "Failed to deserialize workflow input: " + ex.getMessage(), lease);
            return Outcome.FAILED;
        }

        // Load history *before* writing TASK_STARTED so the cursor only sees prior turns.
        List<Event> history = eventRepository.findByWorkflowIdOrderByIdAsc(wf.getId());
        HistoryCursor cursor = new HistoryCursor(wf.getId(), history, objectMapper);
        ReplayState replayState = new ReplayStateImpl(cursor, codec);
        EventLog eventLog = eventLogFactory.create(wf.getId(), lease);

        lifecycle.markRunning(wf, lease);
        eventLog.workflowTaskStarted();

        CommandContext ctx = new CommandContext(wf.getId(), task.getId(),
                replayState, eventLog, lease, codec, dispatcher);
        WorkflowContextHolder.set(ctx);
        try {
            Object result = entryMethod.invoke(bean, callArgs);
            return outcomeMapper.onCompleted(wf, result, eventLog, lease);
        } catch (LockLostException lost) {
            log.warn("[{}] lock lost during turn — discarding writes, another node owns the task", wf.getId());
            return Outcome.LOST;
        } catch (Throwable thrown) {
            try {
                return outcomeMapper.onThrown(wf, thrown, eventLog, lease);
            } catch (LockLostException lost) {
                log.warn("[{}] lock lost during turn — discarding writes, another node owns the task", wf.getId());
                return Outcome.LOST;
            }
        } finally {
            WorkflowContextHolder.clear();
        }
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
