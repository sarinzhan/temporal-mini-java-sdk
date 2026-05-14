package com.beeline.temporalmini;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
public class WorkflowScheduler {

    private final WorkflowRepository workflowRepository;
    private final ThreadPoolTaskExecutor executor;
    private final WorkflowRuntimeRegistry runtimeRegistry;
    private final WorkflowExecutor workflowExecutor;

    public WorkflowScheduler(WorkflowRepository workflowRepository,
                             ThreadPoolTaskExecutor executor,
                             WorkflowRuntimeRegistry runtimeRegistry,
                             WorkflowExecutor workflowExecutor) {
        this.workflowRepository = workflowRepository;
        this.executor = executor;
        this.runtimeRegistry = runtimeRegistry;
        this.workflowExecutor = workflowExecutor;
    }

    @Scheduled(fixedDelayString = "${workflow.scheduler.interval-ms:2000}")
    public void poll() {
        if (executor.getThreadPoolExecutor().getQueue().remainingCapacity() == 0) {
            log.debug("Scheduler: executor queue full, skipping poll");
            return;
        }
        List<WorkflowEntity> pending = workflowRepository.findPendingWorkflows(LocalDateTime.now());
        if (pending.isEmpty()) {
            log.debug("Scheduler: no pending workflows");
            return;
        }
        int submitted = 0;
        for (WorkflowEntity w : pending) {
            if (executor.getThreadPoolExecutor().getQueue().remainingCapacity() == 0) break;
            Long id = w.getId();
            if (!runtimeRegistry.tryStart(id)) continue;
            submitted++;
            executor.execute(() -> {
                runtimeRegistry.markRunning(id);
                try {
                    workflowExecutor.tryExecute(id);
                } finally {
                    runtimeRegistry.finish(id);
                }
            });
        }
        if (submitted > 0) {
            log.info("Scheduler: submitted {} workflow(s) ({} pending, {} already in-flight)",
                    submitted, pending.size(), pending.size() - submitted);
        }
    }
}
