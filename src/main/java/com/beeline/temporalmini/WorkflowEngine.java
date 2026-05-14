package com.beeline.temporalmini;

import com.beeline.temporalmini.metrics.ActivityMetrics;
import com.beeline.temporalmini.metrics.WorkflowMetrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
public class WorkflowEngine {

    private static final Set<WorkflowState> RUN_NOW_FORBIDDEN = EnumSet.of(WorkflowState.FINISHED);
    private static final Set<WorkflowState> STOPPABLE   = EnumSet.of(WorkflowState.NEW, WorkflowState.RETRY);
    private static final Set<WorkflowState> RESUMABLE   = EnumSet.of(WorkflowState.STOPPED, WorkflowState.FAILED);

    private final Map<String, Workflow> registry;
    private final WorkflowRepository workflowRepository;
    private final ActivityRepository activityRepository;
    private final WorkflowHistoryRepository workflowHistoryRepository;
    private final ActivityHistoryRepository activityHistoryRepository;
    private final ObjectMapper objectMapper;
    private final ActivityMetrics activityMetrics;
    private final WorkflowMetrics workflowMetrics;

    public WorkflowEngine(List<Workflow> workflows, WorkflowRepository workflowRepository,
                          ActivityRepository activityRepository,
                          WorkflowHistoryRepository workflowHistoryRepository,
                          ActivityHistoryRepository activityHistoryRepository,
                          ObjectMapper objectMapper,
                          ActivityMetrics activityMetrics, WorkflowMetrics workflowMetrics) {
        this.registry = workflows.stream().collect(Collectors.toMap(Workflow::type, Function.identity()));
        this.workflowRepository = workflowRepository;
        this.activityRepository = activityRepository;
        this.workflowHistoryRepository = workflowHistoryRepository;
        this.activityHistoryRepository = activityHistoryRepository;
        this.objectMapper = objectMapper;
        this.activityMetrics = activityMetrics;
        this.workflowMetrics = workflowMetrics;
    }

    private void recordRun(String workflowType, Workflow workflow, WorkflowContext ctx) throws Exception {
        if (workflowMetrics == null) {
            workflow.run(ctx);
            return;
        }
        workflowMetrics.record(workflowType, () -> { workflow.run(ctx); return null; });
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
        if (workflowMetrics != null) workflowMetrics.recordCreated();
        log.info("[{}:{}] created", workflowType, entity.getId());
        return entity.getId();
    }

    /**
     * Execute one attempt of the workflow.
     *
     * <p>Note: there is no "currently running" persisted state. "In-flight" execution is
     * tracked separately via {@link WorkflowRuntimeRegistry} — see {@link WorkflowState}
     * javadoc for the rationale.
     *
     * <p>Each invocation also appends one row to {@code wflow.workflow_history} (created
     * at entry, finalized on exit with the outcome). Every {@code saveActivity(...)}
     * inside this run mirrors itself into {@code wflow.activity_history}. These tables
     * are append-only and survive {@link #restart(Long)} / {@link #restartFromActivity}
     * — see the "Database schema" section of the README.
     */
    public void run(Long workflowId) {
        WorkflowEntity entity = workflowRepository.findById(workflowId).orElseThrow();
        if (entity.getState() == WorkflowState.STOPPED) {
            log.debug("[{}:{}] skipped — STOPPED", entity.getWorkflowType(), workflowId);
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime eligibleAt = entity.getNextRetryAt() != null
                ? entity.getNextRetryAt()
                : entity.getCreatedAt();
        long pickupDelayMs = eligibleAt != null
                ? Math.max(0L, java.time.Duration.between(eligibleAt, now).toMillis())
                : 0L;

        if (entity.getStartedAt() == null) {
            entity.setStartedAt(now);
        }
        entity.setNextRetryAt(null);

        WorkflowHistoryEntity hist = new WorkflowHistoryEntity();
        hist.setWorkflowId(workflowId);
        hist.setStartedAt(now);
        hist.setInitialState(entity.getState().name());
        hist.setPickupDelayMs(pickupDelayMs);
        hist = workflowHistoryRepository.save(hist);

        Workflow workflow = registry.get(entity.getWorkflowType());
        WorkflowContext ctx = new WorkflowContext(entity, activityRepository,
                activityHistoryRepository, hist.getId(), objectMapper, activityMetrics);
        log.info("[{}:{}] running", entity.getWorkflowType(), workflowId);
        try {
            recordRun(entity.getWorkflowType(), workflow, ctx);
            entity.setState(WorkflowState.FINISHED);
            entity.setFinishedAt(LocalDateTime.now());
            log.info("[{}:{}] FINISHED", entity.getWorkflowType(), workflowId);
        } catch (ActivityException ex) {
            if (entity.getNextRetryAt() != null) {
                entity.setState(WorkflowState.RETRY);
                log.info("[{}:{}] RETRY — next run at {}", entity.getWorkflowType(), workflowId, entity.getNextRetryAt());
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
        hist.setFinishedAt(LocalDateTime.now());
        hist.setOutcome(entity.getState().name());
        hist.setErrorMessage(entity.getErrorMessage());
        hist.setNextRetryAt(entity.getNextRetryAt());
        workflowHistoryRepository.save(hist);
        workflowRepository.save(entity);
    }

    /**
     * Schedule the workflow to be picked up by the scheduler immediately.
     * Allowed for any non-FINISHED state. FAILED and STOPPED workflows are
     * reset to RETRY so the scheduler will pick them up.
     */
    public void runNow(Long workflowId) {
        WorkflowEntity entity = workflowRepository.findById(workflowId).orElseThrow();
        if (RUN_NOW_FORBIDDEN.contains(entity.getState())) {
            throw new IllegalStateException(
                    "Cannot run workflow in state " + entity.getState() + " — already finished");
        }
        entity.setNextRetryAt(LocalDateTime.now());
        if (entity.getState() == WorkflowState.FAILED || entity.getState() == WorkflowState.STOPPED) {
            entity.setState(WorkflowState.RETRY);
            entity.setErrorMessage(null);
        }
        workflowRepository.save(entity);
        log.info("[{}:{}] forced run-now", entity.getWorkflowType(), workflowId);
    }

    /** Move workflow to STOPPED so the scheduler stops picking it up. */
    public void stop(Long workflowId) {
        WorkflowEntity entity = workflowRepository.findById(workflowId).orElseThrow();
        if (!STOPPABLE.contains(entity.getState())) {
            throw new IllegalStateException(
                    "Cannot stop workflow in state " + entity.getState());
        }
        entity.setState(WorkflowState.STOPPED);
        workflowRepository.save(entity);
        log.info("[{}:{}] STOPPED", entity.getWorkflowType(), workflowId);
    }

    /** Resume a STOPPED or FAILED workflow — moves to RETRY so the scheduler resumes it. */
    public void resume(Long workflowId) {
        WorkflowEntity entity = workflowRepository.findById(workflowId).orElseThrow();
        if (!RESUMABLE.contains(entity.getState())) {
            throw new IllegalStateException(
                    "Cannot resume workflow in state " + entity.getState() + " — only STOPPED/FAILED allowed");
        }
        entity.setState(WorkflowState.RETRY);
        entity.setErrorMessage(null);
        entity.setNextRetryAt(LocalDateTime.now());
        workflowRepository.save(entity);
        log.info("[{}:{}] RESUMED", entity.getWorkflowType(), workflowId);
    }

    /**
     * Restart from scratch: wipe all activity rows so the next replay re-executes every
     * step. Reset state to RETRY, clear started/error/retry-at, schedule for immediate
     * pickup. Allowed in any state — operators use this to retry a dead workflow.
     *
     * <p>This is destructive: per-attempt history for this workflow is permanently gone.
     * Callers in the UI should confirm before invoking.
     */
    @Transactional
    public void restart(Long workflowId) {
        WorkflowEntity entity = workflowRepository.findById(workflowId).orElseThrow();
        activityRepository.deleteByWorkflowId(workflowId);
        entity.setState(WorkflowState.RETRY);
        entity.setStartedAt(null);
        entity.setFinishedAt(null);
        entity.setErrorMessage(null);
        entity.setNextRetryAt(LocalDateTime.now());
        workflowRepository.save(entity);
        log.info("[{}:{}] RESTART (full)", entity.getWorkflowType(), workflowId);
    }

    /**
     * Restart starting from a specific activity — every activity row whose
     * {@code startedAt >= chosen.startedAt} is deleted, the workflow is reset to RETRY
     * and queued for immediate pickup. Earlier activities remain in the cache so replay
     * skips over them.
     */
    @Transactional
    public void restartFromActivity(Long workflowId, Long activityId) {
        WorkflowEntity entity = workflowRepository.findById(workflowId).orElseThrow();
        Activity pivot = activityRepository.findById(activityId)
                .orElseThrow(() -> new IllegalArgumentException("Activity not found: " + activityId));
        if (!pivot.getWorkflowId().equals(workflowId)) {
            throw new IllegalArgumentException("Activity " + activityId + " does not belong to workflow " + workflowId);
        }
        if (pivot.getStartedAt() == null) {
            throw new IllegalStateException("Activity has no startedAt — cannot pivot here");
        }
        int deleted = activityRepository.deleteByWorkflowIdAndStartedAtGreaterThanEqual(
                workflowId, pivot.getStartedAt());
        entity.setState(WorkflowState.RETRY);
        entity.setFinishedAt(null);
        entity.setErrorMessage(null);
        entity.setNextRetryAt(LocalDateTime.now());
        workflowRepository.save(entity);
        log.info("[{}:{}] RESTART from activity {} ({}) — {} attempts wiped",
                entity.getWorkflowType(), workflowId, pivot.getName(), pivot.getAttempt(), deleted);
    }

    // ── Bulk helpers — return number of workflows successfully transitioned. Skips,
    //    rather than fails, on illegal transitions so a single bad row in a wide
    //    selection doesn't poison the whole operation. ────────────────────────────

    public int stopAll(Collection<Long> ids) {
        return forEachSilently(ids, this::stop);
    }

    public int resumeAll(Collection<Long> ids) {
        return forEachSilently(ids, this::resume);
    }

    public int restartAll(Collection<Long> ids) {
        return forEachSilently(ids, this::restart);
    }

    public int runNowAll(Collection<Long> ids) {
        return forEachSilently(ids, this::runNow);
    }

    /**
     * Replace the workflow's input payload. Allowed only in non-running, non-finished
     * states — once the workflow has started executing, the cached activity outputs
     * make the input largely irrelevant; once finished, editing it would be misleading.
     */
    private static final Set<WorkflowState> PAYLOAD_EDITABLE =
            EnumSet.of(WorkflowState.NEW, WorkflowState.RETRY, WorkflowState.STOPPED, WorkflowState.FAILED);

    public void setPayload(Long workflowId, String payload) {
        WorkflowEntity entity = workflowRepository.findById(workflowId).orElseThrow();
        if (!PAYLOAD_EDITABLE.contains(entity.getState())) {
            throw new IllegalStateException(
                    "Cannot edit payload in state " + entity.getState() + " — only NEW/STOPPED/FAILED allowed");
        }
        entity.setNextPayload(payload);
        workflowRepository.save(entity);
        log.info("[{}:{}] payload edited", entity.getWorkflowType(), workflowId);
    }

    /**
     * Replace an activity's input or output payload. No state check on the workflow —
     * operators sometimes need to surgically rewrite history before a restart-from
     * (e.g. fix a bad upstream payload that the workflow cached on a successful
     * attempt). Caller takes responsibility.
     */
    public void setActivityPayload(Long workflowId, Long activityId, String payload, boolean output) {
        Activity activity = activityRepository.findById(activityId)
                .orElseThrow(() -> new IllegalArgumentException("Activity not found: " + activityId));
        if (!activity.getWorkflowId().equals(workflowId)) {
            throw new IllegalArgumentException("Activity " + activityId + " does not belong to workflow " + workflowId);
        }
        if (output) activity.setOutputPayload(payload);
        else        activity.setInputPayload(payload);
        activityRepository.save(activity);
        log.info("[{}] activity {} ({}) {} payload edited",
                workflowId, activityId, activity.getName(), output ? "output" : "input");
    }

    private int forEachSilently(Collection<Long> ids, java.util.function.LongConsumer action) {
        int ok = 0;
        for (Long id : ids) {
            try { action.accept(id); ok++; }
            catch (Exception ex) {
                log.warn("bulk op skipped workflow {}: {}", id, ex.getMessage());
            }
        }
        return ok;
    }
}
