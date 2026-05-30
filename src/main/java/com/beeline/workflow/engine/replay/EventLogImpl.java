package com.beeline.workflow.engine.replay;

import com.beeline.workflow.core.model.Event;
import com.beeline.workflow.core.model.EventType;
import com.beeline.workflow.core.model.Schedule;
import com.beeline.workflow.engine.codec.PayloadCodec;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Per-turn EventLog that <b>buffers</b> all writes in memory. Nothing is persisted while the
 * workflow entry method runs — activities can take a long time and we must not hold a DB
 * transaction open for the whole turn. At the end of the turn the {@code WorkflowTurnRunner}
 * flushes the buffer atomically (events + schedule rows + workflow status + task finalize) in a
 * single fenced transaction; see {@code TurnCommitter}.
 *
 * <p>Every append still fences via {@link TaskLease#assertOwned()} so a worker that has already
 * lost its lease stops producing events immediately (fail-fast), and the SQL-level fence at commit
 * time is the durable guarantee.
 *
 * <p>Not thread-safe — one turn runs on a single worker thread.
 */
public final class EventLogImpl implements EventLog {

    private final Long workflowId;
    private final TaskLease lease;
    private final PayloadCodec codec;

    private final List<Event> bufferedEvents = new ArrayList<>();
    private final List<Schedule> bufferedSchedules = new ArrayList<>();

    public EventLogImpl(Long workflowId, TaskLease lease, PayloadCodec codec) {
        this.workflowId = workflowId;
        this.lease = lease;
        this.codec = codec;
    }

    /** Events accumulated this turn, in append order. Flushed by the committer. */
    public List<Event> bufferedEvents() {
        return bufferedEvents;
    }

    /** Schedule rows accumulated this turn (activity retry backoff wake-ups). */
    public List<Schedule> bufferedSchedules() {
        return bufferedSchedules;
    }

    // ── Activity ────────────────────────────────────────────────────────────

    @Override
    public void activityStarted(int seq, String name, int attempt) {
        appendActivity(EventType.ACTIVITY_STARTED, seq, name,
                codec.encodeActivityStartedMarker(attempt));
    }

    @Override
    public void activityCompleted(int seq, String name, int attempt, Object result) {
        appendActivity(EventType.ACTIVITY_COMPLETED, seq, name,
                codec.encodeActivityResult(result, attempt));
    }

    @Override
    public void activityFailed(int seq, String name, int attempt, String reason) {
        appendActivity(EventType.ACTIVITY_FAILED, seq, name,
                codec.encodeActivityFailed(attempt, reason));
    }

    @Override
    public void activityTimedOut(int seq, String name, int attempt, String reason) {
        appendActivity(EventType.ACTIVITY_TIMEOUT, seq, name,
                codec.encodeActivityFailed(attempt, reason));
    }

    @Override
    public void activityRetryScheduled(int seq, String name, int attempt, Instant fireAt, String reason) {
        lease.assertOwned();
        bufferedEvents.add(activityEvent(EventType.ACTIVITY_RETRY_SCHEDULED, seq, name,
                codec.encodeActivityRetryScheduled(attempt, fireAt, reason)));
        Schedule sched = new Schedule();
        sched.setWorkflowId(workflowId);
        sched.setSeq(seq);
        sched.setFireAt(fireAt);
        sched.setReason("retry " + name + " attempt " + attempt);
        sched.setProcessed(false);
        bufferedSchedules.add(sched);
    }

    // ── SideEffect / Version ────────────────────────────────────────────────

    @Override
    public void sideEffectRecorded(int seq, Object result) {
        appendCommand(EventType.SIDE_EFFECT_RECORDED, CommandType.SIDE_EFFECT, seq, null,
                codec.encodeSideEffectResult(result));
    }

    @Override
    public void versionMarker(String changeId, int version) {
        // Markers are looked up by changeId (see HistoryCursor.findVersionMarker) and must NOT occupy
        // a seq slot — otherwise activity/sideEffect replay at the same seq would see a VERSION command
        // and throw NonDeterminismException. seq stays null so they are invisible to seq-based replay.
        appendCommand(EventType.VERSION_MARKER, CommandType.VERSION, null, null,
                codec.encodeVersionMarker(changeId, version));
    }

    // ── Workflow lifecycle ──────────────────────────────────────────────────

    @Override
    public void workflowTaskStarted() {
        appendLifecycle(EventType.WORKFLOW_TASK_STARTED, null);
    }

    @Override
    public void workflowTaskCompleted() {
        appendLifecycle(EventType.WORKFLOW_TASK_COMPLETED, null);
    }

    @Override
    public void workflowTaskFailed(String reason) {
        appendLifecycle(EventType.WORKFLOW_TASK_FAILED, codec.encodeReason(reason));
    }

    @Override
    public void workflowCompleted(String resultJson) {
        appendLifecycle(EventType.WORKFLOW_COMPLETED, resultJson);
    }

    @Override
    public void workflowFailed(String reason) {
        appendLifecycle(EventType.WORKFLOW_FAILED, codec.encodeReason(reason));
    }

    @Override
    public void workflowCreated(String inputJson) {
        appendLifecycle(EventType.WORKFLOW_CREATED, inputJson);
    }

    @Override
    public void workflowTaskQueued(String reason) {
        appendLifecycle(EventType.WORKFLOW_TASK_QUEUED, codec.encodeReason(reason));
    }

    // ── internals ───────────────────────────────────────────────────────────

    private void appendActivity(EventType type, int seq, String name, String payload) {
        appendCommand(type, CommandType.ACTIVITY, seq, name, payload);
    }

    private void appendCommand(EventType type, CommandType cmd, Integer seq, String name, String payload) {
        lease.assertOwned();
        Event e = new Event();
        e.setWorkflowId(workflowId);
        e.setEventType(type);
        e.setCommandType(cmd != null ? cmd.name() : null);
        e.setSeq(seq);
        e.setActivityName(name);
        e.setPayload(payload);
        bufferedEvents.add(e);
    }

    private void appendLifecycle(EventType type, String payload) {
        lease.assertOwned();
        Event e = new Event();
        e.setWorkflowId(workflowId);
        e.setEventType(type);
        e.setPayload(payload);
        bufferedEvents.add(e);
    }

    private Event activityEvent(EventType type, int seq, String name, String payload) {
        Event e = new Event();
        e.setWorkflowId(workflowId);
        e.setEventType(type);
        e.setCommandType(CommandType.ACTIVITY.name());
        e.setSeq(seq);
        e.setActivityName(name);
        e.setPayload(payload);
        return e;
    }
}
