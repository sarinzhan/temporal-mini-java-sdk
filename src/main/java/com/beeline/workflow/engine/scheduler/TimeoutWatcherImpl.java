package com.beeline.workflow.engine.scheduler;

import com.beeline.workflow.persistence.repository.TaskRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;

public class TimeoutWatcherImpl implements TimeoutWatcher {

    private static final Logger log = LoggerFactory.getLogger(TimeoutWatcherImpl.class);

    private final TaskRepository taskRepository;

    public TimeoutWatcherImpl(TaskRepository taskRepository) {
        this.taskRepository = taskRepository;
    }

    @Override
    @Scheduled(fixedDelayString = "${workflow.timeout-watcher-interval-ms:5000}")
    @Transactional
    public void resetStaleTasks() {
        int reset = taskRepository.resetStaleLocks();
        if (reset > 0) {
            log.warn("TimeoutWatcher: reset {} stale PROCESSING tasks back to PENDING", reset);
        }
    }
}
