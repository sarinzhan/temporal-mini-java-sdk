package com.beeline.workflow.engine.command;

import com.beeline.workflow.engine.codec.PayloadCodec;
import com.beeline.workflow.engine.replay.EventLog;
import com.beeline.workflow.engine.replay.ReplayState;
import com.beeline.workflow.engine.replay.TaskLease;

/**
 * Per-turn scaffolding that {@link CommandHandler}s read from. Built by the turn runner and
 * pinned in {@link com.beeline.workflow.engine.context.WorkflowContextHolder} for the lifetime
 * of one workflow decision turn.
 */
public final class CommandContext {

    private final Long workflowId;
    private final Long taskId;
    private final ReplayState replayState;
    private final EventLog eventLog;
    private final TaskLease taskLease;
    private final PayloadCodec codec;
    private final CommandDispatcher dispatcher;

    public CommandContext(Long workflowId,
                          Long taskId,
                          ReplayState replayState,
                          EventLog eventLog,
                          TaskLease taskLease,
                          PayloadCodec codec,
                          CommandDispatcher dispatcher) {
        this.workflowId = workflowId;
        this.taskId = taskId;
        this.replayState = replayState;
        this.eventLog = eventLog;
        this.taskLease = taskLease;
        this.codec = codec;
        this.dispatcher = dispatcher;
    }

    public Long workflowId() { return workflowId; }
    public Long taskId() { return taskId; }
    public ReplayState replayState() { return replayState; }
    public EventLog eventLog() { return eventLog; }
    public TaskLease taskLease() { return taskLease; }
    public PayloadCodec codec() { return codec; }
    public CommandDispatcher dispatcher() { return dispatcher; }
}
