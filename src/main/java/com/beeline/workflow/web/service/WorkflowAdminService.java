package com.beeline.workflow.web.service;

import com.beeline.workflow.core.model.ActivityResult;
import com.beeline.workflow.core.model.Event;
import com.beeline.workflow.core.model.EventType;
import com.beeline.workflow.core.model.RetryRecord;
import com.beeline.workflow.core.model.Signal;
import com.beeline.workflow.core.model.Task;
import com.beeline.workflow.core.model.TaskStatus;
import com.beeline.workflow.core.model.WorkflowInstance;
import com.beeline.workflow.core.model.WorkflowStatus;
import com.beeline.workflow.persistence.repository.ActivityResultRepository;
import com.beeline.workflow.persistence.repository.EventRepository;
import com.beeline.workflow.persistence.repository.RetryRepository;
import com.beeline.workflow.persistence.repository.SignalRepository;
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
    private final ActivityResultRepository activityResultRepository;
    private final RetryRepository retryRepository;
    private final SignalRepository signalRepository;

    public WorkflowAdminService(WorkflowRepository workflowRepository,
                                TaskRepository taskRepository,
                                EventRepository eventRepository,
                                ActivityResultRepository activityResultRepository,
                                RetryRepository retryRepository,
                                SignalRepository signalRepository) {
        this.workflowRepository = workflowRepository;
        this.taskRepository = taskRepository;
        this.eventRepository = eventRepository;
        this.activityResultRepository = activityResultRepository;
        this.retryRepository = retryRepository;
        this.signalRepository = signalRepository;
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

        // Kill any PENDING tasks so the worker doesn't pick them up.
        List<Task> pendings = taskRepository.findByWorkflowIdAndStatus(workflowId, TaskStatus.PENDING);
        for (Task t : pendings) {
            t.setStatus(TaskStatus.DEAD);
        }
        taskRepository.saveAll(pendings);

        // Mark unprocessed retries processed.
        List<RetryRecord> retries = retryRepository.findByWorkflowIdAndProcessedFalseOrderByFireAtAsc(workflowId);
        for (RetryRecord r : retries) r.setProcessed(true);
        retryRepository.saveAll(retries);

        saveEvent(workflowId, EventType.WORKFLOW_CANCELLED, null, null, null);
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

        // Find the most recent task to copy its payload/type — re-enqueue it so worker replays.
        List<Task> hist = taskRepository.findByWorkflowIdOrderByCreatedAtDesc(workflowId);
        if (hist.isEmpty()) {
            throw new IllegalStateException("cannot resume: no prior task to replay from");
        }
        Task last = hist.get(0);
        Task replay = new Task();
        replay.setWorkflowId(workflowId);
        replay.setTaskType(last.getTaskType());
        replay.setStatus(TaskStatus.PENDING);
        replay.setPayload(last.getPayload());
        replay.setScheduledAt(Instant.now());
        taskRepository.save(replay);

        saveEvent(workflowId, EventType.WORKFLOW_RESUMED, null, null, null);
        log.info("Workflow {} resumed by admin action", workflowId);
    }

    @Transactional
    public void retryDeadActivity(Long workflowId, String activityName) {
        ActivityResult ar = activityResultRepository.findByWorkflowIdAndActivityName(workflowId, activityName)
                .orElseThrow(() -> new IllegalArgumentException(
                        "activity not found: " + workflowId + "/" + activityName));
        if (!"DEAD".equals(ar.getStatus()) && !"FAILED".equals(ar.getStatus())) {
            throw new IllegalStateException("activity is not retryable: status=" + ar.getStatus());
        }
        // Reset attempt + status; engine will redo the activity on next workflow replay.
        ar.setStatus("PENDING_RETRY");
        ar.setError(null);
        ar.setAttempt(0);
        activityResultRepository.save(ar);

        // Re-enqueue workflow task so worker replays.
        WorkflowInstance wf = workflowRepository.findById(workflowId).orElseThrow();
        if (wf.getStatus() == WorkflowStatus.FAILED || wf.getStatus() == WorkflowStatus.CANCELLED) {
            wf.setStatus(WorkflowStatus.RUNNING);
            wf.setError(null);
            wf.setCompletedAt(null);
            wf.setUpdatedAt(Instant.now());
            workflowRepository.save(wf);
        }
        Task t = new Task();
        t.setWorkflowId(workflowId);
        t.setTaskType("RETRY_ACTIVITY");
        t.setStatus(TaskStatus.PENDING);
        t.setPayload(null);
        t.setScheduledAt(Instant.now());
        taskRepository.save(t);

        saveEvent(workflowId, EventType.ACTIVITY_RETRYING, activityName, 0, "force-retry by user");
        log.info("Activity {}/{} force-retried by admin action", workflowId, activityName);
    }

    @Transactional
    public void sendSignal(Long workflowId, String signalName, String payload) {
        if (signalName == null || signalName.isBlank()) {
            throw new IllegalArgumentException("signalName is required");
        }
        Signal s = new Signal();
        s.setWorkflowId(workflowId);
        s.setSignalName(signalName);
        s.setPayload(payload);
        s.setConsumed(false);
        signalRepository.save(s);
        saveEvent(workflowId, EventType.SIGNAL_SENT, signalName, null, payload);
        log.info("Signal {} sent to workflow {}", signalName, workflowId);
    }

    private void saveEvent(Long workflowId, EventType type, String activityName, Integer attempt, String data) {
        Event e = new Event();
        e.setWorkflowId(workflowId);
        e.setEventType(type);
        e.setActivityName(activityName);
        e.setAttempt(attempt);
        e.setData(data);
        eventRepository.save(e);
    }
}
