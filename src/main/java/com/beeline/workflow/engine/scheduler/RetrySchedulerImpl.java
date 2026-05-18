package com.beeline.workflow.engine.scheduler;

import com.beeline.workflow.core.model.RetryRecord;
import com.beeline.workflow.core.model.Task;
import com.beeline.workflow.core.model.TaskStatus;
import com.beeline.workflow.persistence.repository.RetryRepository;
import com.beeline.workflow.persistence.repository.TaskRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

public class RetrySchedulerImpl implements RetryScheduler {

    private static final Logger log = LoggerFactory.getLogger(RetrySchedulerImpl.class);

    private static final int BATCH_SIZE = 100;

    private final RetryRepository retryRepository;
    private final TaskRepository taskRepository;

    public RetrySchedulerImpl(RetryRepository retryRepository, TaskRepository taskRepository) {
        this.retryRepository = retryRepository;
        this.taskRepository = taskRepository;
    }

    @Override
    @Scheduled(fixedDelayString = "${workflow.retry-poll-interval-ms:2000}")
    @Transactional
    public void pollDueRetries() {
        List<RetryRecord> due = retryRepository.pollDue(Instant.now(), BATCH_SIZE);
        if (due.isEmpty()) return;

        for (RetryRecord r : due) {
            Task t = new Task();
            t.setWorkflowId(r.getWorkflowId());
            t.setTaskType("workflow.retry");
            t.setStatus(TaskStatus.PENDING);
            t.setScheduledAt(Instant.now());
            taskRepository.save(t);

            r.setProcessed(true);
            retryRepository.save(r);
        }
        log.debug("RetryScheduler: enqueued {} retry tasks", due.size());
    }
}
