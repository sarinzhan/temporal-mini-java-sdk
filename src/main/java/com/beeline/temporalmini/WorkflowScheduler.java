package com.beeline.temporalmini;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
public class WorkflowScheduler {

    private final WorkflowEngine engine;
    private final WorkflowRepository workflowRepository;

    public WorkflowScheduler(WorkflowEngine engine, WorkflowRepository workflowRepository) {
        this.engine = engine;
        this.workflowRepository = workflowRepository;
    }

    @Scheduled(fixedDelayString = "${workflow.scheduler.interval-ms:5000}")
    public void poll() {
        List<WorkflowEntity> pending = workflowRepository.findPendingWorkflows(LocalDateTime.now());
        if (pending.isEmpty()) {
            log.debug("Scheduler: no pending workflows");
            return;
        }
        log.info("Scheduler: picked up {} workflow(s)", pending.size());
        pending.forEach(w -> engine.run(w.getId()));
    }
}
