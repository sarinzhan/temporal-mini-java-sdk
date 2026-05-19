package com.beeline.workflow.spring.api;

import com.beeline.workflow.core.model.Task;
import com.beeline.workflow.core.model.TaskStatus;
import com.beeline.workflow.core.model.WorkflowInstance;
import com.beeline.workflow.core.model.WorkflowStatus;
import com.beeline.workflow.persistence.repository.TaskRepository;
import com.beeline.workflow.persistence.repository.WorkflowRepository;
import com.beeline.workflow.registry.WorkflowRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;

public class WorkflowClientImpl implements WorkflowClient {

    private static final Logger log = LoggerFactory.getLogger(WorkflowClientImpl.class);

    private final WorkflowRepository workflowRepository;
    private final TaskRepository taskRepository;
    private final WorkflowRegistry workflowRegistry;
    private final ObjectMapper objectMapper;

    public WorkflowClientImpl(WorkflowRepository workflowRepository,
                              TaskRepository taskRepository,
                              WorkflowRegistry workflowRegistry,
                              ObjectMapper objectMapper) {
        this.workflowRepository = workflowRepository;
        this.taskRepository = taskRepository;
        this.workflowRegistry = workflowRegistry;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public Long startWorkflow(String workflowType, Object input) {
        if (!workflowRegistry.contains(workflowType)) {
            throw new IllegalArgumentException("Unknown workflow type: " + workflowType);
        }
        WorkflowInstance wf = new WorkflowInstance();
        wf.setWorkflowType(workflowType);
        wf.setStatus(WorkflowStatus.PENDING);
        wf.setInput(serialize(input));
        wf.setCreatedAt(Instant.now());
        wf.setUpdatedAt(Instant.now());
        workflowRepository.save(wf);

        Task t = new Task();
        t.setWorkflowId(wf.getId());
        t.setTaskType("workflow.start");
        t.setStatus(TaskStatus.PENDING);
        t.setScheduledAt(Instant.now());
        taskRepository.save(t);

        log.info("Workflow {} ({}) started, task {} enqueued", wf.getId(), workflowType, t.getId());
        return wf.getId();
    }

    private String serialize(Object value) {
        if (value == null) return null;
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize workflow input", e);
        }
    }
}
