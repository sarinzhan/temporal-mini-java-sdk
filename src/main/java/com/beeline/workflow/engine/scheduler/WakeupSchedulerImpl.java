package com.beeline.workflow.engine.scheduler;

import com.beeline.workflow.core.model.Event;
import com.beeline.workflow.core.model.EventType;
import com.beeline.workflow.core.model.Schedule;
import com.beeline.workflow.core.model.Task;
import com.beeline.workflow.core.model.TaskStatus;
import com.beeline.workflow.persistence.repository.EventRepository;
import com.beeline.workflow.persistence.repository.ScheduleRepository;
import com.beeline.workflow.persistence.repository.TaskRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Polls {@code wflow.schedule} for due rows and enqueues a workflow task per affected workflow,
 * so a parked workflow (e.g. one waiting out an activity retry backoff) re-runs and replays.
 * The source of truth for replay is {@code wflow.events}; schedule rows only control timing.
 */
public class WakeupSchedulerImpl implements WakeupScheduler {

    private static final Logger log = LoggerFactory.getLogger(WakeupSchedulerImpl.class);

    private static final int BATCH_SIZE = 100;

    private final ScheduleRepository scheduleRepository;
    private final EventRepository eventRepository;
    private final TaskRepository taskRepository;

    public WakeupSchedulerImpl(ScheduleRepository scheduleRepository,
                               EventRepository eventRepository,
                               TaskRepository taskRepository) {
        this.scheduleRepository = scheduleRepository;
        this.eventRepository = eventRepository;
        this.taskRepository = taskRepository;
    }

    @Override
    @Scheduled(fixedDelayString = "${workflow.retry-poll-interval-ms:2000}")
    @Transactional
    public void pollAndFire() {
        Instant now = Instant.now();
        List<Schedule> due = scheduleRepository.pollDue(now, BATCH_SIZE);
        if (due.isEmpty()) return;

        Set<Long> workflowsToEnqueue = new HashSet<>();
        for (Schedule s : due) {
            s.setProcessed(true);
            scheduleRepository.save(s);
            workflowsToEnqueue.add(s.getWorkflowId());
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
        log.debug("Wakeup: fired {} schedule rows, enqueued {} workflows",
                due.size(), workflowsToEnqueue.size());
    }
}
