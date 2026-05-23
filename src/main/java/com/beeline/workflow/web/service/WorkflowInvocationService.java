package com.beeline.workflow.web.service;

import com.beeline.workflow.core.model.Event;
import com.beeline.workflow.core.model.EventType;
import com.beeline.workflow.core.model.Task;
import com.beeline.workflow.core.model.TaskStatus;
import com.beeline.workflow.core.model.UpdateRequest;
import com.beeline.workflow.core.model.WorkflowInstance;
import com.beeline.workflow.engine.query.WorkflowQueryRuntime;
import com.beeline.workflow.engine.replay.CommandType;
import com.beeline.workflow.engine.update.UpdateRegistry;
import com.beeline.workflow.persistence.repository.EventRepository;
import com.beeline.workflow.persistence.repository.TaskRepository;
import com.beeline.workflow.persistence.repository.UpdateRequestRepository;
import com.beeline.workflow.persistence.repository.WorkflowRepository;
import com.beeline.workflow.registry.WorkflowRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class WorkflowInvocationService {

    private static final Logger log = LoggerFactory.getLogger(WorkflowInvocationService.class);

    private final WorkflowQueryRuntime queryRuntime;
    private final WorkflowRegistry workflowRegistry;
    private final WorkflowRepository workflowRepository;
    private final EventRepository eventRepository;
    private final TaskRepository taskRepository;
    private final UpdateRequestRepository updateRequestRepository;
    private final UpdateRegistry updateRegistry;
    private final ObjectMapper objectMapper;

    public WorkflowInvocationService(WorkflowQueryRuntime queryRuntime,
                                     WorkflowRegistry workflowRegistry,
                                     WorkflowRepository workflowRepository,
                                     EventRepository eventRepository,
                                     TaskRepository taskRepository,
                                     UpdateRequestRepository updateRequestRepository,
                                     UpdateRegistry updateRegistry,
                                     ObjectMapper objectMapper) {
        this.queryRuntime = queryRuntime;
        this.workflowRegistry = workflowRegistry;
        this.workflowRepository = workflowRepository;
        this.eventRepository = eventRepository;
        this.taskRepository = taskRepository;
        this.updateRequestRepository = updateRequestRepository;
        this.updateRegistry = updateRegistry;
        this.objectMapper = objectMapper;
    }

    public Object query(Long workflowId, String queryName, List<Object> args) {
        return queryRuntime.runQuery(workflowId, queryName, args);
    }

    @Transactional
    public String dispatchUpdate(Long workflowId, String updateName, List<Object> args) {
        WorkflowInstance wf = workflowRepository.findById(workflowId)
                .orElseThrow(() -> new IllegalArgumentException("workflow not found: " + workflowId));
        if (workflowRegistry.getUpdateMethod(wf.getWorkflowType(), updateName) == null) {
            throw new IllegalArgumentException(
                    "no @UpdateMethod named '" + updateName + "' on " + wf.getWorkflowType());
        }

        String updateId = UUID.randomUUID().toString();
        String argsJson;
        try {
            argsJson = objectMapper.writeValueAsString(args != null ? args : List.of());
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize update args", e);
        }

        UpdateRequest req = new UpdateRequest();
        req.setUpdateId(updateId);
        req.setWorkflowId(workflowId);
        req.setMethodName(updateName);
        req.setArgsPayload(argsJson);
        updateRequestRepository.save(req);

        Event requested = new Event();
        requested.setWorkflowId(workflowId);
        requested.setEventType(EventType.UPDATE_REQUESTED);
        requested.setCommandType(CommandType.UPDATE.name());
        requested.setActivityName(updateName);
        requested.setPayload("{\"updateId\":\"" + updateId + "\",\"args\":" + argsJson + "}");
        eventRepository.save(requested);

        Task task = new Task();
        task.setWorkflowId(workflowId);
        task.setTaskType("workflow.update");
        task.setStatus(TaskStatus.PENDING);
        task.setScheduledAt(Instant.now());
        taskRepository.save(task);

        Event queued = new Event();
        queued.setWorkflowId(workflowId);
        queued.setEventType(EventType.WORKFLOW_TASK_QUEUED);
        queued.setPayload("{\"reason\":\"update\",\"updateId\":\"" + updateId + "\"}");
        eventRepository.save(queued);

        log.info("Update {} dispatched to workflow {} method={}", updateId, workflowId, updateName);
        return updateId;
    }

    public UpdateRegistry.UpdateResult awaitUpdate(String updateId, long timeoutMs) {
        CompletableFuture<UpdateRegistry.UpdateResult> f = updateRegistry.registerPending(updateId);
        try {
            return f.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException te) {
            return new UpdateRegistry.UpdateResult(false, null, "timeout");
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return new UpdateRegistry.UpdateResult(false, null, "interrupted");
        } catch (ExecutionException ee) {
            return new UpdateRegistry.UpdateResult(false, null, ee.getMessage());
        }
    }
}
