package com.beeline.temporalmini;

import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
public class WorkflowEngine {

    private static final Set<WorkflowState> RUN_NOW_FORBIDDEN = EnumSet.of(WorkflowState.FINISHED);
    private static final Set<WorkflowState> BLOCKABLE = EnumSet.of(WorkflowState.NEW, WorkflowState.RUNNABLE);
    private static final Set<WorkflowState> UNBLOCKABLE = EnumSet.of(WorkflowState.BLOCKED);

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

    /**
     * Execute one attempt of the workflow.
     *
     * <p>Note: the persisted state stays {@code RUNNABLE} for the entire duration of an
     * active run. "Currently running" is tracked separately via {@link WorkflowRuntimeRegistry},
     * not in the database — see {@link WorkflowState} javadoc for the rationale.
     */
    public void run(Long workflowId) {
        WorkflowEntity entity = workflowRepository.findById(workflowId).orElseThrow();
        if (entity.getState() == WorkflowState.BLOCKED) {
            log.debug("[{}:{}] skipped — BLOCKED", entity.getWorkflowType(), workflowId);
            return;
        }
        entity.setState(WorkflowState.RUNNABLE);
        if (entity.getStartedAt() == null) {
            entity.setStartedAt(LocalDateTime.now());
        }
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

    /**
     * Schedule the workflow to be picked up by the scheduler immediately.
     * Allowed for any non-FINISHED state. FAILED and BLOCKED workflows are
     * reset to RUNNABLE so the scheduler will pick them up.
     */
    public void runNow(Long workflowId) {
        WorkflowEntity entity = workflowRepository.findById(workflowId).orElseThrow();
        if (RUN_NOW_FORBIDDEN.contains(entity.getState())) {
            throw new IllegalStateException(
                    "Cannot run workflow in state " + entity.getState() + " — already finished");
        }
        entity.setNextRetryAt(LocalDateTime.now());
        if (entity.getState() == WorkflowState.FAILED || entity.getState() == WorkflowState.BLOCKED) {
            entity.setState(WorkflowState.RUNNABLE);
            entity.setErrorMessage(null);
        }
        workflowRepository.save(entity);
        log.info("[{}:{}] forced run-now", entity.getWorkflowType(), workflowId);
    }

    /** Move workflow to BLOCKED so the scheduler stops picking it up. */
    public void block(Long workflowId) {
        WorkflowEntity entity = workflowRepository.findById(workflowId).orElseThrow();
        if (!BLOCKABLE.contains(entity.getState())) {
            throw new IllegalStateException(
                    "Cannot block workflow in state " + entity.getState());
        }
        entity.setState(WorkflowState.BLOCKED);
        workflowRepository.save(entity);
        log.info("[{}:{}] BLOCKED", entity.getWorkflowType(), workflowId);
    }

    /** Resume a BLOCKED workflow — moves to RUNNABLE so the scheduler resumes it. */
    public void unblock(Long workflowId) {
        WorkflowEntity entity = workflowRepository.findById(workflowId).orElseThrow();
        if (!UNBLOCKABLE.contains(entity.getState())) {
            throw new IllegalStateException(
                    "Cannot unblock workflow in state " + entity.getState() + " — only BLOCKED is allowed");
        }
        entity.setState(WorkflowState.RUNNABLE);
        workflowRepository.save(entity);
        log.info("[{}:{}] UNBLOCKED", entity.getWorkflowType(), workflowId);
    }
}
