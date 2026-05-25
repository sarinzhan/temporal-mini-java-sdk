package com.beeline.workflow.engine.signal;

import com.beeline.workflow.core.model.Event;
import com.beeline.workflow.core.model.EventType;
import com.beeline.workflow.core.model.PendingAwait;
import com.beeline.workflow.core.model.Signal;
import com.beeline.workflow.core.model.Task;
import com.beeline.workflow.core.model.TaskStatus;
import com.beeline.workflow.persistence.repository.EventRepository;
import com.beeline.workflow.persistence.repository.PendingAwaitRepository;
import com.beeline.workflow.persistence.repository.SignalRepository;
import com.beeline.workflow.persistence.repository.TaskRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;

public class SignalBusImpl implements SignalBus {

    private static final Logger log = LoggerFactory.getLogger(SignalBusImpl.class);

    private final SignalRepository signalRepository;
    private final EventRepository eventRepository;
    private final TaskRepository taskRepository;
    private final PendingAwaitRepository pendingAwaitRepository;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    public SignalBusImpl(SignalRepository signalRepository,
                         EventRepository eventRepository,
                         TaskRepository taskRepository,
                         PendingAwaitRepository pendingAwaitRepository,
                         ObjectMapper objectMapper,
                         PlatformTransactionManager transactionManager) {
        this.signalRepository = signalRepository;
        this.eventRepository = eventRepository;
        this.taskRepository = taskRepository;
        this.pendingAwaitRepository = pendingAwaitRepository;
        this.objectMapper = objectMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    @Transactional
    public void send(Long workflowId, String signalName, Object payload) {
        String payloadJson = serialize(payload);

        Signal s = new Signal();
        s.setWorkflowId(workflowId);
        s.setSignalName(signalName);
        s.setPayload(payloadJson);
        s.setConsumed(false);
        signalRepository.save(s);

        Event received = new Event();
        received.setWorkflowId(workflowId);
        received.setEventType(EventType.SIGNAL_RECEIVED);
        received.setActivityName(signalName);
        received.setPayload(payloadJson);
        eventRepository.save(received);

        // Nudge the workflow: if there are pending awaits, enqueue a workflow task
        // so the worker re-evaluates the await condition.
        List<PendingAwait> awaits = pendingAwaitRepository.findByWorkflowId(workflowId);
        if (!awaits.isEmpty()) {
            Task task = new Task();
            task.setWorkflowId(workflowId);
            task.setTaskType("workflow.signal");
            task.setStatus(TaskStatus.PENDING);
            task.setScheduledAt(Instant.now());
            taskRepository.save(task);

            Event queued = new Event();
            queued.setWorkflowId(workflowId);
            queued.setEventType(EventType.WORKFLOW_TASK_QUEUED);
            queued.setPayload("{\"reason\":\"signal\",\"signal\":\"" + escapeJson(signalName) + "\"}");
            eventRepository.save(queued);
        }

        log.info("Signal sent to workflow {} name={}", workflowId, signalName);
    }

    private String serialize(Object value) {
        if (value == null) return null;
        if (value instanceof String str) return str;
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize signal payload", e);
        }
    }

    private static String escapeJson(String v) {
        if (v == null) return "";
        return v.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
