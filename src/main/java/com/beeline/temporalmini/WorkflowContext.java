package com.beeline.temporalmini;

import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.function.Supplier;

@Slf4j
public class WorkflowContext {

    private final WorkflowEntity workflowEntity;
    private final ActivityRepository activityRepository;
    private final ObjectMapper objectMapper;

    public WorkflowContext(WorkflowEntity workflowEntity,
                           ActivityRepository activityRepository, ObjectMapper objectMapper) {
        this.workflowEntity = workflowEntity;
        this.activityRepository = activityRepository;
        this.objectMapper = objectMapper;
    }

    public <T> T activity(String name, Class<T> resultType, RetryPolicy retryPolicy, Supplier<T> fn) {
        Activity existing = activityRepository.findSuccessfulActivity(workflowEntity.getId(), name).orElse(null);
        if (existing != null) {
            log.debug("[{}:{}] Activity {} — skipped (already succeeded on attempt {})",
                    workflowEntity.getWorkflowType(), workflowEntity.getId(), name, existing.getAttempt());
            return deserialize(existing.getOutputPayload(), resultType);
        }
        int attempt = activityRepository.countByWorkflowIdAndName(workflowEntity.getId(), name) + 1;
        LocalDateTime startedAt = LocalDateTime.now();
        try {
            T result = fn.get();
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
        Activity existing = activityRepository.findSuccessfulActivity(workflowEntity.getId(), name).orElse(null);
        if (existing != null) {
            log.debug("[{}:{}] Activity {} — skipped (already succeeded on attempt {})",
                    workflowEntity.getWorkflowType(), workflowEntity.getId(), name, existing.getAttempt());
            return;
        }
        int attempt = activityRepository.countByWorkflowIdAndName(workflowEntity.getId(), name) + 1;
        LocalDateTime startedAt = LocalDateTime.now();
        try {
            fn.run();
            saveActivity(name, attempt, true, startedAt, null, null, null);
            log.info("[{}:{}] Activity {} — OK (attempt {}/{})",
                    workflowEntity.getWorkflowType(), workflowEntity.getId(), name, attempt, retryPolicy.getMaxAttempts());
        } catch (Exception ex) {
            saveActivity(name, attempt, false, startedAt, null, null, ex.getMessage());
            scheduleRetry(name, attempt, retryPolicy, ex);
        }
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
