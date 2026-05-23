package com.beeline.workflow.engine.scheduler;

import com.beeline.workflow.core.model.Event;
import com.beeline.workflow.core.model.EventType;
import com.beeline.workflow.core.model.PendingAwait;
import com.beeline.workflow.core.model.PendingTimer;
import com.beeline.workflow.core.model.RetryRecord;
import com.beeline.workflow.core.model.Task;
import com.beeline.workflow.core.model.TaskStatus;
import com.beeline.workflow.engine.replay.CommandType;
import com.beeline.workflow.persistence.repository.EventRepository;
import com.beeline.workflow.persistence.repository.PendingAwaitRepository;
import com.beeline.workflow.persistence.repository.PendingTimerRepository;
import com.beeline.workflow.persistence.repository.RetryRepository;
import com.beeline.workflow.persistence.repository.TaskRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class WakeupSchedulerImpl implements WakeupScheduler {

    private static final Logger log = LoggerFactory.getLogger(WakeupSchedulerImpl.class);

    private static final int BATCH_SIZE = 100;

    private final RetryRepository retryRepository;
    private final PendingTimerRepository timerRepository;
    private final PendingAwaitRepository awaitRepository;
    private final EventRepository eventRepository;
    private final TaskRepository taskRepository;

    public WakeupSchedulerImpl(RetryRepository retryRepository,
                               PendingTimerRepository timerRepository,
                               PendingAwaitRepository awaitRepository,
                               EventRepository eventRepository,
                               TaskRepository taskRepository) {
        this.retryRepository = retryRepository;
        this.timerRepository = timerRepository;
        this.awaitRepository = awaitRepository;
        this.eventRepository = eventRepository;
        this.taskRepository = taskRepository;
    }

    @Override
    @Scheduled(fixedDelayString = "${workflow.retry-poll-interval-ms:2000}")
    @Transactional
    public void pollAndFire() {
        Instant now = Instant.now();
        Set<Long> workflowsToEnqueue = new HashSet<>();

        // 1. Activity retries.
        List<RetryRecord> dueRetries = retryRepository.pollDue(now, BATCH_SIZE);
        for (RetryRecord r : dueRetries) {
            r.setProcessed(true);
            retryRepository.save(r);
            workflowsToEnqueue.add(r.getWorkflowId());
            // Audit event for "manual retry kicked in" already written by AdminService / executor;
            // we only need to drive the worker.
        }

        // 2. Timers (sleep).
        List<PendingTimer> dueTimers = timerRepository.pollDue(now, BATCH_SIZE);
        for (PendingTimer t : dueTimers) {
            Event fired = new Event();
            fired.setWorkflowId(t.getWorkflowId());
            fired.setEventType(EventType.TIMER_FIRED);
            fired.setCommandType(CommandType.TIMER.name());
            fired.setSeq(t.getSeq());
            fired.setPayload("{\"firedAt\":\"" + now + "\"}");
            eventRepository.save(fired);
            timerRepository.delete(t);
            workflowsToEnqueue.add(t.getWorkflowId());
        }

        // 3. Awaits — timeout path.
        List<PendingAwait> dueAwaits = awaitRepository.pollDueByDeadline(now, BATCH_SIZE);
        for (PendingAwait a : dueAwaits) {
            Event fired = new Event();
            fired.setWorkflowId(a.getWorkflowId());
            fired.setEventType(EventType.AWAIT_FIRED);
            fired.setCommandType(CommandType.AWAIT.name());
            fired.setSeq(a.getSeq());
            fired.setPayload("{\"reason\":\"timeout\",\"firedAt\":\"" + now + "\"}");
            eventRepository.save(fired);
            awaitRepository.delete(a);
            workflowsToEnqueue.add(a.getWorkflowId());
        }

        for (Long workflowId : workflowsToEnqueue) {
            Task t = new Task();
            t.setWorkflowId(workflowId);
            t.setTaskType("workflow.wakeup");
            t.setStatus(TaskStatus.PENDING);
            t.setScheduledAt(now);
            taskRepository.save(t);

            Event queued = new Event();
            queued.setWorkflowId(workflowId);
            queued.setEventType(EventType.WORKFLOW_TASK_QUEUED);
            queued.setPayload("{\"reason\":\"wakeup\"}");
            eventRepository.save(queued);
        }

        if (!workflowsToEnqueue.isEmpty()) {
            log.debug("Wakeup: enqueued {} workflows ({} retries, {} timers, {} awaits)",
                    workflowsToEnqueue.size(), dueRetries.size(), dueTimers.size(), dueAwaits.size());
        }
    }
}
