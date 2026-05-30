package com.beeline.workflow.engine.replay;

import com.beeline.workflow.core.model.Event;
import com.beeline.workflow.core.model.EventType;
import com.beeline.workflow.core.model.Schedule;
import com.beeline.workflow.engine.codec.PayloadCodec;
import com.beeline.workflow.persistence.repository.EventRepository;
import com.beeline.workflow.persistence.repository.ScheduleRepository;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;

/**
 * Per-turn EventLog: bound to one (workflowId, lease) and writes through the shared
 * {@link EventRepository}/{@link ScheduleRepository} with a {@link TransactionTemplate}. Every
 * write fences via {@link TaskLease#assertOwned()} so a stale worker can never persist after
 * its lease was reclaimed.
 */
public final class EventLogImpl implements EventLog {

    private final Long workflowId;
    private final TaskLease lease;
    private final EventRepository eventRepository;
    private final ScheduleRepository scheduleRepository;
    private final TransactionTemplate transactionTemplate;
    private final PayloadCodec codec;

    public EventLogImpl(Long workflowId,
                        TaskLease lease,
                        EventRepository eventRepository,
                        ScheduleRepository scheduleRepository,
                        TransactionTemplate transactionTemplate,
                        PayloadCodec codec) {
        this.workflowId = workflowId;
        this.lease = lease;
        this.eventRepository = eventRepository;
        this.scheduleRepository = scheduleRepository;
        this.transactionTemplate = transactionTemplate;
        this.codec = codec;
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
    public void activityRetryScheduled(int seq, String name, int attempt, Instant fireAt, String reason) {
        lease.assertOwned();
        transactionTemplate.executeWithoutResult(s -> {
            eventRepository.save(activityEvent(EventType.ACTIVITY_RETRY_SCHEDULED, seq, name,
                    codec.encodeActivityRetryScheduled(attempt, fireAt, reason)));
            Schedule sched = new Schedule();
            sched.setWorkflowId(workflowId);
            sched.setSeq(seq);
            sched.setFireAt(fireAt);
            sched.setReason("retry " + name + " attempt " + attempt);
            sched.setProcessed(false);
            scheduleRepository.save(sched);
        });
    }

    // ── SideEffect / Version ────────────────────────────────────────────────

    @Override
    public void sideEffectRecorded(int seq, Object result) {
        appendCommand(EventType.SIDE_EFFECT_RECORDED, CommandType.SIDE_EFFECT, seq, null,
                codec.encodeSideEffectResult(result));
    }

    @Override
    public void versionMarker(int seq, String changeId, int version) {
        appendCommand(EventType.VERSION_MARKER, CommandType.VERSION, seq, null,
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
    public void workflowCompleted(String resultJson) {
        appendLifecycle(EventType.WORKFLOW_COMPLETED, resultJson);
    }

    @Override
    public void workflowFailed(String reason) {
        appendLifecycle(EventType.WORKFLOW_FAILED, reason);
    }

    @Override
    public void workflowCreated(String inputJson) {
        appendLifecycle(EventType.WORKFLOW_CREATED, inputJson);
    }

    @Override
    public void workflowTaskQueued(String reason) {
        appendLifecycle(EventType.WORKFLOW_TASK_QUEUED, "{\"reason\":\"" + reason + "\"}");
    }

    // ── internals ───────────────────────────────────────────────────────────

    private void appendActivity(EventType type, int seq, String name, String payload) {
        appendCommand(type, CommandType.ACTIVITY, seq, name, payload);
    }

    private void appendCommand(EventType type, CommandType cmd, Integer seq, String name, String payload) {
        lease.assertOwned();
        transactionTemplate.executeWithoutResult(s -> {
            Event e = new Event();
            e.setWorkflowId(workflowId);
            e.setEventType(type);
            e.setCommandType(cmd != null ? cmd.name() : null);
            e.setSeq(seq);
            e.setActivityName(name);
            e.setPayload(payload);
            eventRepository.save(e);
        });
    }

    private void appendLifecycle(EventType type, String payload) {
        lease.assertOwned();
        transactionTemplate.executeWithoutResult(s -> {
            Event e = new Event();
            e.setWorkflowId(workflowId);
            e.setEventType(type);
            e.setPayload(payload);
            eventRepository.save(e);
        });
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
