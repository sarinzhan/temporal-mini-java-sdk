package com.beeline.temporalmini;

import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
public class WorkflowEngine {

    private final Map<String, Workflow> registry;
    private final WorkflowRepository workflowRepository;
    private final ActivityRepository activityRepository;
    private final ObjectMapper objectMapper;

    public WorkflowEngine(List<Workflow> workflows, WorkflowRepository workflowRepository,
                          ActivityRepository activityRepository, ObjectMapper objectMapper) {
        this.registry = workflows.stream().collect(Collectors.toMap(Workflow::type, Function.identity()));
        this.workflowRepository = workflowRepository;
        this.activityRepository = activityRepository;
        this.objectMapper = objectMapper;
    }

    public Long start(String workflowType, String initialPayload) {
        if (!registry.containsKey(workflowType)) {
            throw new IllegalArgumentException("Unknown workflow type: " + workflowType);
        }
        WorkflowEntity entity = new WorkflowEntity();
        entity.setWorkflowType(workflowType);
        entity.setNextPayload(initialPayload);
        entity.setState(WorkflowState.NEW);
        entity.setCreatedAt(LocalDateTime.now());
        workflowRepository.save(entity);
        log.info("[{}:{}] created", workflowType, entity.getId());
        return entity.getId();
    }

    public void run(Long workflowId) {
        WorkflowEntity entity = workflowRepository.findById(workflowId).orElseThrow();
        entity.setState(WorkflowState.RUNNING);
        entity.setStartedAt(LocalDateTime.now());
        entity.setNextRetryAt(null);

        Workflow workflow = registry.get(entity.getWorkflowType());
        WorkflowContext ctx = new WorkflowContext(entity, activityRepository, objectMapper);
        log.info("[{}:{}] running", entity.getWorkflowType(), workflowId);
        try {
            workflow.run(ctx);
            entity.setState(WorkflowState.FINISHED);
            log.info("[{}:{}] FINISHED", entity.getWorkflowType(), workflowId);
        } catch (ActivityException ex) {
            if (entity.getNextRetryAt() != null) {
                log.info("[{}:{}] waiting — next run at {}", entity.getWorkflowType(), workflowId, entity.getNextRetryAt());
            } else {
                entity.setState(WorkflowState.FAILED);
                entity.setErrorMessage(ex.getMessage());
                log.error("[{}:{}] FAILED — activity exhausted all retries: {}",
                        entity.getWorkflowType(), workflowId, ex.getMessage());
            }
        } catch (Exception ex) {
            entity.setState(WorkflowState.FAILED);
            entity.setErrorMessage(ex.getMessage());
            log.error("[{}:{}] FAILED — unexpected error: {}",
                    entity.getWorkflowType(), workflowId, ex.getMessage());
        }
        workflowRepository.save(entity);
    }
}
