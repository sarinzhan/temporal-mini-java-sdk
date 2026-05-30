package com.beeline.workflow.engine.replay;

import com.beeline.workflow.engine.codec.PayloadCodec;
import com.beeline.workflow.persistence.repository.EventRepository;
import com.beeline.workflow.persistence.repository.ScheduleRepository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Spring-managed factory that produces a per-turn {@link EventLog} bound to a (workflowId, lease).
 * Singleton-scoped; the per-turn state lives only in the returned {@link EventLogImpl}.
 */
public final class EventLogFactory {

    private final EventRepository eventRepository;
    private final ScheduleRepository scheduleRepository;
    private final TransactionTemplate transactionTemplate;
    private final PayloadCodec codec;

    public EventLogFactory(EventRepository eventRepository,
                           ScheduleRepository scheduleRepository,
                           PlatformTransactionManager transactionManager,
                           PayloadCodec codec) {
        this.eventRepository = eventRepository;
        this.scheduleRepository = scheduleRepository;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.codec = codec;
    }

    public EventLog create(Long workflowId, TaskLease lease) {
        return new EventLogImpl(workflowId, lease, eventRepository, scheduleRepository,
                transactionTemplate, codec);
    }
}
