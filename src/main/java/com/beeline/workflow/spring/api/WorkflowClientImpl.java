package com.beeline.workflow.spring.api;

import com.beeline.workflow.core.model.Event;
import com.beeline.workflow.core.model.EventType;
import com.beeline.workflow.core.model.Task;
import com.beeline.workflow.core.model.TaskStatus;
import com.beeline.workflow.core.model.WorkflowInstance;
import com.beeline.workflow.core.model.WorkflowStatus;
import com.beeline.workflow.persistence.repository.EventRepository;
import com.beeline.workflow.persistence.repository.TaskRepository;
import com.beeline.workflow.persistence.repository.WorkflowRepository;
import com.beeline.workflow.registry.WorkflowRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.time.Instant;

public class WorkflowClientImpl implements WorkflowClient {

    private static final Logger log = LoggerFactory.getLogger(WorkflowClientImpl.class);

    private final WorkflowRepository workflowRepository;
    private final TaskRepository taskRepository;
    private final EventRepository eventRepository;
    private final WorkflowRegistry workflowRegistry;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    public WorkflowClientImpl(WorkflowRepository workflowRepository,
                              TaskRepository taskRepository,
                              EventRepository eventRepository,
                              WorkflowRegistry workflowRegistry,
                              ObjectMapper objectMapper,
                              PlatformTransactionManager transactionManager) {
        this.workflowRepository = workflowRepository;
        this.taskRepository = taskRepository;
        this.eventRepository = eventRepository;
        this.workflowRegistry = workflowRegistry;
        this.objectMapper = objectMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    public <R> WorkflowHandle<R> start(Class<?> workflowInterface, Object input) {
        String type = resolveType(workflowInterface);
        Method entry = workflowRegistry.getEntryMethod(type);
        if (entry == null) {
            throw new IllegalStateException("No @WorkflowMethod registered for " + type);
        }
        Type resultType = entry.getGenericReturnType();
        WorkflowInstance wf = writeStartState(type, serialize(input));
        return new WorkflowHandleImpl<>(wf.getId(), type, resultType, workflowRepository, objectMapper);
    }

    @Override
    public Long startByType(String workflowType, String inputJson) {
        if (!workflowRegistry.contains(workflowType)) {
            throw new IllegalArgumentException("Unknown workflow type: " + workflowType);
        }
        return writeStartState(workflowType, inputJson).getId();
    }

    private String resolveType(Class<?> iface) {
        String type = workflowRegistry.getTypeForInterface(iface);
        if (type == null) {
            throw new IllegalStateException(
                    "No @WorkflowComponent registered for interface " + iface.getName() +
                    ". Make sure an implementing bean annotated with @WorkflowComponent is on the classpath.");
        }
        return type;
    }

    protected WorkflowInstance writeStartState(String workflowType, String inputJson) {
        return transactionTemplate.execute(status -> {
            WorkflowInstance wf = new WorkflowInstance();
            wf.setWorkflowType(workflowType);
            wf.setStatus(WorkflowStatus.PENDING);
            wf.setInput(inputJson);
            wf.setCreatedAt(Instant.now());
            wf.setUpdatedAt(Instant.now());
            workflowRepository.save(wf);

            Event created = new Event();
            created.setWorkflowId(wf.getId());
            created.setEventType(EventType.WORKFLOW_CREATED);
            created.setPayload(wf.getInput());
            eventRepository.save(created);

            Task t = new Task();
            t.setWorkflowId(wf.getId());
            t.setTaskType("workflow.start");
            t.setStatus(TaskStatus.PENDING);
            t.setScheduledAt(Instant.now());
            taskRepository.save(t);

            Event queued = new Event();
            queued.setWorkflowId(wf.getId());
            queued.setEventType(EventType.WORKFLOW_TASK_QUEUED);
            queued.setPayload("{\"reason\":\"start\"}");
            eventRepository.save(queued);

            log.info("Workflow {} ({}) started, task {} enqueued", wf.getId(), workflowType, t.getId());
            return wf;
        });
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
