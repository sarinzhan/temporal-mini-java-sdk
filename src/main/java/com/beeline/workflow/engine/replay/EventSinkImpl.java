package com.beeline.workflow.engine.replay;

import com.beeline.workflow.core.model.Event;
import com.beeline.workflow.core.model.EventType;
import com.beeline.workflow.persistence.repository.EventRepository;
import org.springframework.transaction.support.TransactionTemplate;

public final class EventSinkImpl implements EventSink {

    private final Long workflowId;
    private final EventRepository eventRepository;
    private final TransactionTemplate transactionTemplate;

    public EventSinkImpl(Long workflowId,
                         EventRepository eventRepository,
                         TransactionTemplate transactionTemplate) {
        this.workflowId = workflowId;
        this.eventRepository = eventRepository;
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    public void append(EventType type,
                       CommandType commandType,
                       Integer seq,
                       String activityName,
                       String payload) {
        transactionTemplate.executeWithoutResult(s -> {
            Event e = new Event();
            e.setWorkflowId(workflowId);
            e.setEventType(type);
            e.setCommandType(commandType != null ? commandType.name() : null);
            e.setSeq(seq);
            e.setActivityName(activityName);
            e.setPayload(payload);
            eventRepository.save(e);
        });
    }
}
