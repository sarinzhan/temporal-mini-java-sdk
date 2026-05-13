package com.beeline.temporalmini;

import com.beeline.temporalmini.metrics.ActivityMetrics;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.function.Supplier;

@Slf4j
public class WorkflowContext {

    private final WorkflowEntity workflowEntity;
    private final ActivityRepository activityRepository;
    private final ActivityHistoryRepository activityHistoryRepository;
    private final Long workflowHistoryId;
    private final ObjectMapper objectMapper;
    private final ActivityMetrics activityMetrics;

    public WorkflowContext(WorkflowEntity workflowEntity,
                           ActivityRepository activityRepository,
                           ActivityHistoryRepository activityHistoryRepository,
                           Long workflowHistoryId,
                           ObjectMapper objectMapper,
                           ActivityMetrics activityMetrics) {
        this.workflowEntity = workflowEntity;
        this.activityRepository = activityRepository;
        this.activityHistoryRepository = activityHistoryRepository;
        this.workflowHistoryId = workflowHistoryId;
        this.objectMapper = objectMapper;
        this.activityMetrics = activityMetrics;
    }

    public <T> T activity(String name, Class<T> resultType, RetryPolicy retryPolicy, Supplier<T> fn) {

        // search
        Activity existing = activityRepository.findSuccessfulActivity(workflowEntity.getId(), name).orElse(null);
        if (existing != null) {
            log.debug("[{}:{}] Activity {} — skipped (already succeeded on attempt {})",
                    workflowEntity.getWorkflowType(), workflowEntity.getId(), name, existing.getAttempt());
            return deserialize(existing.getOutputPayload(), resultType);
        }


        // run
        int attempt = activityRepository.countByWorkflowIdAndName(workflowEntity.getId(), name) + 1;
        LocalDateTime startedAt = LocalDateTime.now();
        try {
            T result = recordCallable(name, workflowEntity.getWorkflowType(), fn::get);
            saveActivity(name, attempt, true, startedAt, null, serialize(result), null);
            log.info("[{}:{}] Activity {} — OK (attempt {}/{})",
                    workflowEntity.getWorkflowType(), workflowEntity.getId(), name, attempt, retryPolicy.getMaxAttempts());
            return result;
        } catch (Exception ex) {
            saveActivity(name, attempt, false, startedAt, null, null, ex.getMessage());
            scheduleRetry(name, attempt, retryPolicy, ex);
            throw new RuntimeException("unreachable");
        }
    }

    public void activity(String name, RetryPolicy retryPolicy, Runnable fn) {

        // search result
        Activity existing = activityRepository.findSuccessfulActivity(workflowEntity.getId(), name).orElse(null);
        if (existing != null) {
            log.debug("[{}:{}] Activity {} — skipped (already succeeded on attempt {})",
                    workflowEntity.getWorkflowType(), workflowEntity.getId(), name, existing.getAttempt());
            return;
        }

        // call
        int attempt = activityRepository.countByWorkflowIdAndName(workflowEntity.getId(), name) + 1;
        LocalDateTime startedAt = LocalDateTime.now();
        try {
            recordCallable(name, workflowEntity.getWorkflowType(), () -> { fn.run(); return null; });
            saveActivity(name, attempt, true, startedAt, null, null, null);
            log.info("[{}:{}] Activity {} — OK (attempt {}/{})",
                    workflowEntity.getWorkflowType(), workflowEntity.getId(), name, attempt, retryPolicy.getMaxAttempts());
        } catch (Exception ex) {
            saveActivity(name, attempt, false, startedAt, null, null, ex.getMessage());
            scheduleRetry(name, attempt, retryPolicy, ex);
        }
    }




    private <T> T recordCallable(String workflowName, String activityName, java.util.concurrent.Callable<T> fn) throws Exception {
        if (activityMetrics == null) {
            return fn.call();
        }
        return activityMetrics.record(workflowName, activityName, fn);
    }

    private void scheduleRetry(String name, int attempt, RetryPolicy retryPolicy, Exception ex) {
        if (attempt >= retryPolicy.getMaxAttempts()) {
            log.error("[{}:{}] Activity {} — EXHAUSTED after {}/{} attempts: {}",
                    workflowEntity.getWorkflowType(), workflowEntity.getId(),
                    name, attempt, retryPolicy.getMaxAttempts(), ex.getMessage());
            throw new ActivityException(name, ex);
        }
        long delayMs = retryPolicy.delayMs(attempt);
        workflowEntity.setNextRetryAt(LocalDateTime.now().plus(delayMs, ChronoUnit.MILLIS));
        log.warn("[{}:{}] Activity {} — FAILED (attempt {}/{}, retry at {}): {}",
                workflowEntity.getWorkflowType(), workflowEntity.getId(),
                name, attempt, retryPolicy.getMaxAttempts(), workflowEntity.getNextRetryAt(), ex.getMessage());
        throw new ActivityException(name, ex);
    }

    private void saveActivity(String name, int attempt, boolean success, LocalDateTime startedAt,
                               String inputPayload, String outputPayload, String errorMessage) {
        Activity activity = new Activity();
        activity.setWorkflowId(workflowEntity.getId());
        activity.setName(name);
        activity.setAttempt(attempt);
        activity.setSuccess(success);
        activity.setStartedAt(startedAt);
        activity.setFinishedAt(LocalDateTime.now());
        activity.setInputPayload(inputPayload);
        activity.setOutputPayload(outputPayload);
        activity.setErrorMessage(errorMessage);
        activityRepository.save(activity);

        ActivityHistoryEntity hist = new ActivityHistoryEntity();
        hist.setWorkflowHistoryId(workflowHistoryId);
        hist.setWorkflowId(workflowEntity.getId());
        hist.setActivityId(activity.getId());
        hist.setName(name);
        hist.setAttempt(attempt);
        hist.setSuccess(success);
        hist.setStartedAt(startedAt);
        hist.setFinishedAt(activity.getFinishedAt());
        hist.setInputPayload(inputPayload);
        hist.setOutputPayload(outputPayload);
        hist.setErrorMessage(errorMessage);
        activityHistoryRepository.save(hist);
    }

    private <T> T deserialize(String payload, Class<T> type) {
        if (payload == null) return null;
        try {
            return objectMapper.readValue(payload, type);
        } catch (Exception e) {
            throw new RuntimeException("Failed to deserialize activity result", e);
        }
    }

    private String serialize(Object value) {
        if (value == null) return null;
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize activity result", e);
        }
    }
}
