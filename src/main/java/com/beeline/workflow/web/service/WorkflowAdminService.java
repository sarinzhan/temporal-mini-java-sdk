package com.beeline.workflow.web.service;

import com.beeline.workflow.core.model.Event;
import com.beeline.workflow.core.model.EventType;
import com.beeline.workflow.core.model.RetryRecord;
import com.beeline.workflow.core.model.Task;
import com.beeline.workflow.core.model.TaskStatus;
import com.beeline.workflow.core.model.WorkflowInstance;
import com.beeline.workflow.core.model.WorkflowStatus;
import com.beeline.workflow.engine.signal.SignalBus;
import com.beeline.workflow.persistence.repository.EventRepository;
import com.beeline.workflow.persistence.repository.RetryRepository;
import com.beeline.workflow.persistence.repository.TaskRepository;
import com.beeline.workflow.persistence.repository.WorkflowRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

public class WorkflowAdminService {

    private static final Logger log = LoggerFactory.getLogger(WorkflowAdminService.class);

    private final WorkflowRepository workflowRepository;
    private final TaskRepository taskRepository;
    private final EventRepository eventRepository;
    private final RetryRepository retryRepository;
    private final SignalBus signalBus;

    public WorkflowAdminService(WorkflowRepository workflowRepository,
                                TaskRepository taskRepository,
                                EventRepository eventRepository,
                                RetryRepository retryRepository,
                                SignalBus signalBus) {
        this.workflowRepository = workflowRepository;
        this.taskRepository = taskRepository;
        this.eventRepository = eventRepository;
        this.retryRepository = retryRepository;
        this.signalBus = signalBus;
    }

    @Transactional
    public void cancel(Long workflowId) {
        WorkflowInstance wf = workflowRepository.findById(workflowId)
                .orElseThrow(() -> new IllegalArgumentException("workflow not found: " + workflowId));
        if (wf.getStatus() == WorkflowStatus.COMPLETED || wf.getStatus() == WorkflowStatus.FAILED) {
            throw new IllegalStateException("workflow is terminal, cannot cancel: " + wf.getStatus());
        }
        wf.setStatus(WorkflowStatus.CANCELLED);
        wf.setError("Cancelled by user");
        wf.setCompletedAt(Instant.now());
        wf.setUpdatedAt(Instant.now());
        workflowRepository.save(wf);

        List<Task> pendings = taskRepository.findByWorkflowIdAndStatus(workflowId, TaskStatus.PENDING);
        for (Task t : pendings) t.setStatus(TaskStatus.DEAD);
        taskRepository.saveAll(pendings);

        List<RetryRecord> retries = retryRepository.findByWorkflowIdAndProcessedFalseOrderByFireAtAsc(workflowId);
        for (RetryRecord r : retries) r.setProcessed(true);
        retryRepository.saveAll(retries);

        saveEvent(workflowId, EventType.WORKFLOW_CANCELLED, null, null);
        log.info("Workflow {} cancelled by admin action", workflowId);
    }

    @Transactional
    public void resume(Long workflowId) {
        WorkflowInstance wf = workflowRepository.findById(workflowId)
                .orElseThrow(() -> new IllegalArgumentException("workflow not found: " + workflowId));
        if (wf.getStatus() != WorkflowStatus.CANCELLED) {
            throw new IllegalStateException("only CANCELLED workflows can be resumed, got " + wf.getStatus());
        }
        wf.setStatus(WorkflowStatus.RUNNING);
        wf.setError(null);
        wf.setCompletedAt(null);
        wf.setUpdatedAt(Instant.now());
        workflowRepository.save(wf);

        Task replay = new Task();
        replay.setWorkflowId(workflowId);
        replay.setTaskType("workflow.resume");
        replay.setStatus(TaskStatus.PENDING);
        replay.setScheduledAt(Instant.now());
        taskRepository.save(replay);
        saveEvent(workflowId, EventType.WORKFLOW_TASK_QUEUED, null, "{\"reason\":\"resume\"}");

        log.info("Workflow {} resumed by admin action", workflowId);
    }

    /**
     * Force-retry a failed activity. Writes ACTIVITY_RETRY_SCHEDULED so the next replay
     * re-executes the activity at the same seq, and enqueues a workflow task to drive it.
     */
    @Transactional
    public void retryDeadActivity(Long workflowId, String activityName) {
        WorkflowInstance wf = workflowRepository.findById(workflowId)
                .orElseThrow(() -> new IllegalArgumentException("workflow not found: " + workflowId));

        List<Event> events = eventRepository.findByWorkflowIdOrderByIdAsc(workflowId);
        Event lastFailed = null;
        for (int i = events.size() - 1; i >= 0; i--) {
            Event e = events.get(i);
            if (e.getEventType() == EventType.ACTIVITY_FAILED && activityName.equals(e.getActivityName())) {
                lastFailed = e;
                break;
            }
        }
        if (lastFailed == null) {
            throw new IllegalArgumentException(
                    "no ACTIVITY_FAILED event found for " + workflowId + "/" + activityName);
        }

        if (wf.getStatus() == WorkflowStatus.FAILED || wf.getStatus() == WorkflowStatus.CANCELLED) {
            wf.setStatus(WorkflowStatus.RUNNING);
            wf.setError(null);
            wf.setCompletedAt(null);
            wf.setUpdatedAt(Instant.now());
            workflowRepository.save(wf);
        }

        Event retry = new Event();
        retry.setWorkflowId(workflowId);
        retry.setEventType(EventType.ACTIVITY_RETRY_SCHEDULED);
        retry.setCommandType("ACTIVITY");
        retry.setSeq(lastFailed.getSeq());
        retry.setActivityName(activityName);
        retry.setPayload("{\"fireAt\":\"" + Instant.now() + "\",\"manual\":true}");
        eventRepository.save(retry);

        Task t = new Task();
        t.setWorkflowId(workflowId);
        t.setTaskType("workflow.retry");
        t.setStatus(TaskStatus.PENDING);
        t.setScheduledAt(Instant.now());
        taskRepository.save(t);
        saveEvent(workflowId, EventType.WORKFLOW_TASK_QUEUED, null, "{\"reason\":\"manual-retry\"}");

        log.info("Activity {}/{} force-retried by admin action", workflowId, activityName);
    }

    public void sendSignal(Long workflowId, String signalName, String payload) {
        if (signalName == null || signalName.isBlank()) {
            throw new IllegalArgumentException("signalName is required");
        }
        // Delegate to SignalBus so the SIGNAL_RECEIVED event is recorded and the workflow is
        // nudged — a raw signals-table insert would never reach a parked workflow.
        signalBus.send(workflowId, signalName, payload);
        log.info("Signal {} sent to workflow {}", signalName, workflowId);
    }

    private void saveEvent(Long workflowId, EventType type, String activityName, String payload) {
        Event e = new Event();
        e.setWorkflowId(workflowId);
        e.setEventType(type);
        e.setActivityName(activityName);
        e.setPayload(payload);
        eventRepository.save(e);
    }
}
