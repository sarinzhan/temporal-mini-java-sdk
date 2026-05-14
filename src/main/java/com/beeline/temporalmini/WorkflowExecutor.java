package com.beeline.temporalmini;

import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
public class WorkflowExecutor {

    private final WorkflowRepository workflowRepository;
    private final WorkflowEngine engine;

    public WorkflowExecutor(WorkflowRepository workflowRepository, WorkflowEngine engine) {
        this.workflowRepository = workflowRepository;
        this.engine = engine;
    }

    @Transactional
    public void tryExecute(Long id) {
        if (workflowRepository.findByIdForUpdateSkipLocked(id).isEmpty()) {
            log.debug("Executor: workflow {} already locked by another instance, skipping", id);
            return;
        }
        try {
            engine.run(id);
        } catch (Exception ex) {
            log.error("Executor: unexpected error running workflow {}", id, ex);
        }
    }
}
