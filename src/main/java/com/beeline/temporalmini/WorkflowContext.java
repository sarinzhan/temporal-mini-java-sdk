package com.beeline.temporalmini;

import com.beeline.temporalmini.metrics.ActivityMetrics;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.function.Function;
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

    /**
     * Run an activity with no input. The result type is captured at runtime via
     * {@code result.getClass().getName()} and stored alongside the JSON output, so
     * replays can deserialize back to the original type without the caller having to
     * pass a {@code Class<T>} token.
     */
    @SuppressWarnings("unchecked")
    public <T> T activity(String name, RetryPolicy retryPolicy, Supplier<T> fn) {
        Activity existing = activityRepository.findSuccessfulActivity(workflowEntity.getId(), name).orElse(null);
        if (existing != null) {
            log.debug("[{}:{}] Activity {} — skipped (already succeeded on attempt {})",
                    workflowEntity.getWorkflowType(), workflowEntity.getId(), name, existing.getAttempt());
            return (T) replayResult(existing);
        }

        Activity live = activityRepository.findFirstByWorkflowIdAndNameOrderByIdDesc(workflowEntity.getId(), name).orElse(null);
        int attempt = (live != null ? live.getAttempt() : 0) + 1;
        LocalDateTime startedAt = LocalDateTime.now();
        try {
            T result = recordCallable(name, workflowEntity.getWorkflowType(), fn::get);
            upsertActivity(live, name, attempt, true, startedAt, null, serialize(result), typeOf(result), null);
            log.info("[{}:{}] Activity {} — OK (attempt {}/{})",
                    workflowEntity.getWorkflowType(), workflowEntity.getId(), name, attempt, retryPolicy.getMaxAttempts());
            return result;
        } catch (Exception ex) {
            upsertActivity(live, name, attempt, false, startedAt, null, null, null, ex.getMessage());
            scheduleRetry(name, attempt, retryPolicy, ex);
            throw new RuntimeException("unreachable");
        }
    }

    /**
     * Run an activity that takes a typed input. The input is serialized to JSON and
     * persisted to {@code wflow.activity.input_payload}; the function is invoked with
     * the original (non-serialized) instance. Output type capture works the same as
     * the no-input overload.
     */
    @SuppressWarnings("unchecked")
    public <I, O> O activity(String name, I input, RetryPolicy retryPolicy, Function<I, O> fn) {
        Activity existing = activityRepository.findSuccessfulActivity(workflowEntity.getId(), name).orElse(null);
        if (existing != null) {
            log.debug("[{}:{}] Activity {} — skipped (already succeeded on attempt {})",
                    workflowEntity.getWorkflowType(), workflowEntity.getId(), name, existing.getAttempt());
            return (O) replayResult(existing);
        }

        Activity live = activityRepository.findFirstByWorkflowIdAndNameOrderByIdDesc(workflowEntity.getId(), name).orElse(null);
        int attempt = (live != null ? live.getAttempt() : 0) + 1;
        LocalDateTime startedAt = LocalDateTime.now();
        String inputJson = serialize(input);
        try {
            O result = recordCallable(name, workflowEntity.getWorkflowType(), () -> fn.apply(input));
            upsertActivity(live, name, attempt, true, startedAt, inputJson, serialize(result), typeOf(result), null);
            log.info("[{}:{}] Activity {} — OK (attempt {}/{})",
                    workflowEntity.getWorkflowType(), workflowEntity.getId(), name, attempt, retryPolicy.getMaxAttempts());
            return result;
        } catch (Exception ex) {
            upsertActivity(live, name, attempt, false, startedAt, inputJson, null, null, ex.getMessage());
            scheduleRetry(name, attempt, retryPolicy, ex);
            throw new RuntimeException("unreachable");
        }
    }

    /**
     * Run a side-effecting activity that returns no value. Nothing is stored in
     * {@code output_payload}/{@code output_type}.
     */
    public void activity(String name, RetryPolicy retryPolicy, Runnable fn) {
        Activity existing = activityRepository.findSuccessfulActivity(workflowEntity.getId(), name).orElse(null);
        if (existing != null) {
            log.debug("[{}:{}] Activity {} — skipped (already succeeded on attempt {})",
                    workflowEntity.getWorkflowType(), workflowEntity.getId(), name, existing.getAttempt());
            return;
        }

        Activity live = activityRepository.findFirstByWorkflowIdAndNameOrderByIdDesc(workflowEntity.getId(), name).orElse(null);
        int attempt = (live != null ? live.getAttempt() : 0) + 1;
        LocalDateTime startedAt = LocalDateTime.now();
        try {
            recordCallable(name, workflowEntity.getWorkflowType(), () -> { fn.run(); return null; });
            upsertActivity(live, name, attempt, true, startedAt, null, null, null, null);
            log.info("[{}:{}] Activity {} — OK (attempt {}/{})",
                    workflowEntity.getWorkflowType(), workflowEntity.getId(), name, attempt, retryPolicy.getMaxAttempts());
        } catch (Exception ex) {
            upsertActivity(live, name, attempt, false, startedAt, null, null, null, ex.getMessage());
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

    /**
     * Upsert the live {@code wflow.activity} row for {@code (workflow_id, name)} — one
     * row per activity per workflow, updated in place across retries. The live row
     * always reflects the LATEST attempt; {@code startedAt} is the first attempt's start
     * (preserved across retries so the natural ordering of activities by {@code startedAt}
     * stays stable for restartFromActivity). {@code finishedAt} is set only when the
     * activity has successfully completed — while it is still retrying it stays
     * {@code null} so callers can tell at a glance whether the activity has actually
     * finished. Every call also appends a fresh row to {@code wflow.activity_history}
     * recording this individual attempt (history's {@code finishedAt} is the end of
     * THIS attempt regardless of outcome, for full audit timing).
     */
    private void upsertActivity(Activity live, String name, int attempt, boolean success, LocalDateTime attemptStartedAt,
                                String inputPayload, String outputPayload, String outputType, String errorMessage) {
        LocalDateTime attemptFinishedAt = LocalDateTime.now();
        Activity activity = live != null ? live : new Activity();
        if (live == null) {
            activity.setWorkflowId(workflowEntity.getId());
            activity.setName(name);
            activity.setStartedAt(attemptStartedAt);
        }
        activity.setAttempt(attempt);
        activity.setSuccess(success);
        if (success) {
            activity.setFinishedAt(attemptFinishedAt);
        }
        activity.setInputPayload(inputPayload);
        activity.setOutputPayload(outputPayload);
        activity.setOutputType(outputType);
        activity.setErrorMessage(errorMessage);
        activityRepository.save(activity);

        ActivityHistoryEntity hist = new ActivityHistoryEntity();
        hist.setWorkflowHistoryId(workflowHistoryId);
        hist.setWorkflowId(workflowEntity.getId());
        hist.setActivityId(activity.getId());
        hist.setName(name);
        hist.setAttempt(attempt);
        hist.setSuccess(success);
        hist.setStartedAt(attemptStartedAt);
        hist.setFinishedAt(attemptFinishedAt);
        hist.setInputPayload(inputPayload);
        hist.setOutputPayload(outputPayload);
        hist.setOutputType(outputType);
        hist.setErrorMessage(errorMessage);
        activityHistoryRepository.save(hist);
    }

    /**
     * Reconstruct an activity's cached result on replay. Uses the FQN persisted in
     * {@code outputType} alongside the JSON in {@code outputPayload}; if either is
     * missing returns {@code null} (matches the original behavior for void/null
     * results). Throws {@link IllegalStateException} if the stored class is no longer
     * available on the classpath (workflow code was renamed/removed) — the operator
     * needs to act on that explicitly.
     */
    private Object replayResult(Activity activity) {
        String json = activity.getOutputPayload();
        String typeName = activity.getOutputType();
        if (json == null || typeName == null) return null;
        Class<?> type;
        try {
            type = Class.forName(typeName);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("Cached output type '" + typeName + "' is no longer on the classpath", e);
        }
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            throw new RuntimeException("Failed to deserialize activity result", e);
        }
    }

    private static String typeOf(Object value) {
        return value != null ? value.getClass().getName() : null;
    }

    private String serialize(Object value) {
        if (value == null) return null;
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize activity payload", e);
        }
    }
}
