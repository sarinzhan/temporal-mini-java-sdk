package com.beeline.temporalmini;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.Executor;

@Slf4j
public class WorkflowScheduler {

    private final WorkflowEngine engine;
    private final WorkflowRepository workflowRepository;
    private final Executor executor;
    private final WorkflowRuntimeRegistry runtimeRegistry;

    public WorkflowScheduler(WorkflowEngine engine,
                             WorkflowRepository workflowRepository,
                             Executor executor,
                             WorkflowRuntimeRegistry runtimeRegistry) {
        this.engine = engine;
        this.workflowRepository = workflowRepository;
        this.executor = executor;
        this.runtimeRegistry = runtimeRegistry;
    }

    @Scheduled(fixedDelayString = "${workflow.scheduler.interval-ms:5000}")
    public void poll() {
        List<WorkflowEntity> pending = workflowRepository.findPendingWorkflows(LocalDateTime.now());
        if (pending.isEmpty()) {
            log.debug("Scheduler: no pending workflows");
            return;
        }
        int submitted = 0;
        for (WorkflowEntity w : pending) {
            Long id = w.getId();
            if (!runtimeRegistry.tryStart(id)) continue;
            submitted++;
            executor.execute(() -> {
                runtimeRegistry.markRunning(id);
                try {
                    engine.run(id);
                } catch (Exception ex) {
                    log.error("Scheduler: unexpected error running workflow {}", id, ex);
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
