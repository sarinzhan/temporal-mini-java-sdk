package com.beeline.workflow.sam.core.engine;

import com.beeline.workflow.core.api.Worker;
import com.beeline.workflow.sam.storage.model.Event;
import com.beeline.workflow.sam.storage.model.EventType;
import com.beeline.workflow.sam.storage.model.Schedule;
import com.beeline.workflow.sam.storage.model.WorkflowInstance;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;
import java.util.UUID;

public class WorkflowEngine implements com.beeline.workflow.sam.api.WorkflowEngine {
    private final Worker worker;
    private final TransactionTemplate transactionTemplate;

    public WorkflowEngine(
            Worker worker,
            TransactionTemplate transactionTemplate
    ) {
        this.worker = worker;
        this.transactionTemplate = transactionTemplate;
    }

    public Object startWorkflow(Class<?> iface, String method, Object[] args) {

        transactionTemplate.execute((String) -> {
            WorkflowInstance instance = new WorkflowInstance();
            instance.setClassType(iface.getName());
            instance.setId(UUID.randomUUID());
            instance.setInputPayload(serialize(args[0]));
            instance.setInputType(args[0].getClass().getName());
            instance.setCreatedAt(OffsetDateTime.now());

            Schedule schedule = new Schedule();
            schedule.setWorkflowId(instance.getId());
            schedule.setCreatedAt(OffsetDateTime.now());
            schedule.setFireAt(OffsetDateTime.now());

            Event event = new Event();
            event.setCreatedAt(OffsetDateTime.now());
            event.setEventType(EventType.WORKFLOW_CREATED);
            event.setWorkflowId(instance.getId());

            return null;

        });



        // в реальности тут была бы очередь

    }


    private String serialize(Object obj){
        ObjectMapper mapper = new ObjectMapper();
        try {
            return mapper.writeValueAsString(obj);
        }catch (Exception e){
            throw new RuntimeException("Couldn't serialize", e);
        }

    }
}
