package com.beeline.workflow.engine.replay;

import com.beeline.workflow.core.model.Event;
import com.beeline.workflow.core.model.EventType;
import com.beeline.workflow.persistence.repository.EventRepository;
import org.springframework.transaction.support.TransactionTemplate;

public final class EventSinkImpl implements EventSink {

    private final Long workflowId;
    private final EventRepository eventRepository;
    private final TransactionTemplate transactionTemplate;
    private final TaskLease lease;

    public EventSinkImpl(Long workflowId,
                         EventRepository eventRepository,
                         TransactionTemplate transactionTemplate,
                         TaskLease lease) {
        this.workflowId = workflowId;
        this.eventRepository = eventRepository;
        this.transactionTemplate = transactionTemplate;
        this.lease = lease;
    }

    @Override
    public void append(EventType type,
                       CommandType commandType,
                       Integer seq,
                       String activityName,
                       String payload) {
        lease.assertOwned();
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
