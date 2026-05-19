package com.beeline.workflow.engine.executor;

import com.beeline.workflow.core.api.WorkflowContext;
import com.beeline.workflow.core.config.ActivityOptions;
import com.beeline.workflow.core.config.RetryPolicy;
import com.beeline.workflow.core.exception.ActivityFailureException;
import com.beeline.workflow.core.exception.ActivityTimeoutException;
import com.beeline.workflow.core.exception.NonRetryableException;
import com.beeline.workflow.core.model.ActivityResult;
import com.beeline.workflow.core.model.Event;
import com.beeline.workflow.core.model.EventType;
import com.beeline.workflow.core.model.RetryRecord;
import com.beeline.workflow.engine.context.WorkflowContextHolder;
import com.beeline.workflow.engine.context.WorkflowContextImpl;
import com.beeline.workflow.persistence.repository.ActivityResultRepository;
import com.beeline.workflow.persistence.repository.EventRepository;
import com.beeline.workflow.persistence.repository.RetryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.ObjectMapper;

import java.lang.reflect.Type;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

public class ActivityExecutorImpl implements ActivityExecutor {

    private static final Logger log = LoggerFactory.getLogger(ActivityExecutorImpl.class);

    private final ActivityResultRepository activityResultRepository;
    private final EventRepository eventRepository;
    private final RetryRepository retryRepository;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;
    private final ExecutorService activityThreadPool;
    private final java.util.function.BiFunction<String, ActivityOptions, ActivityOptions> optionsResolver;

    public ActivityExecutorImpl(ActivityResultRepository activityResultRepository,
                                EventRepository eventRepository,
                                RetryRepository retryRepository,
                                ObjectMapper objectMapper,
                                PlatformTransactionManager transactionManager) {
        this(activityResultRepository, eventRepository, retryRepository, objectMapper, transactionManager, (name, opts) -> opts);
    }

    public ActivityExecutorImpl(ActivityResultRepository activityResultRepository,
                                EventRepository eventRepository,
                                RetryRepository retryRepository,
                                ObjectMapper objectMapper,
                                PlatformTransactionManager transactionManager,
                                java.util.function.BiFunction<String, ActivityOptions, ActivityOptions> optionsResolver) {
        this.activityResultRepository = activityResultRepository;
        this.eventRepository = eventRepository;
        this.retryRepository = retryRepository;
        this.objectMapper = objectMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.optionsResolver = optionsResolver;
        this.activityThreadPool = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "wf-activity-" + System.nanoTime());
            t.setDaemon(true);
            return t;
        });
    }

    @Override
    public Object execute(String activityName,
                          ActivityOptions options,
                          Type returnType,
                          Supplier<Object> invocation) {
        WorkflowContext ctx = WorkflowContextHolder.require();
        Long workflowId = ctx.getWorkflowId();
        options = optionsResolver.apply(activityName, options);

        Long wfId = workflowId;
        String actName = activityName;
        Optional<ActivityResult> existing = transactionTemplate.execute(s ->
                activityResultRepository.findByWorkflowIdAndActivityName(wfId, actName));

        if (existing.isPresent() && "COMPLETED".equals(existing.get().getStatus())) {
            log.debug("[{}/{}] activity replay-cached", workflowId, activityName);
            Type effective = returnType;
            if (effective == null || effective == void.class || effective == Void.class) {
                String storedType = existing.get().getResultType();
                if (storedType != null) {
                    try {
                        effective = Class.forName(storedType);
                    } catch (ClassNotFoundException e) {
                        throw new IllegalStateException(
                                "Cached result type not on classpath: " + storedType +
                                " (workflow=" + workflowId + ", activity=" + activityName + ")", e);
                    }
                }
            }
            return deserialize(existing.get().getResult(), effective);
        }

        int attempt = existing.map(r -> r.getAttempt() + 1).orElse(1);

        if (options.getIdempotencyKey() != null && existing.isEmpty()) {
            log.debug("[{}/{}] idempotency key set: {}", workflowId, activityName, options.getIdempotencyKey());
        }

        saveEvent(workflowId, EventType.ACTIVITY_STARTED, activityName, attempt, null);

        Object result;
        Duration timeout = options.getStartToCloseTimeout();
        try {
            CompletableFuture<Object> future = CompletableFuture.supplyAsync(invocation, activityThreadPool);
            if (timeout == null || timeout.isZero() || timeout.isNegative()) {
                result = future.join();
            } else {
                result = future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            }
        } catch (TimeoutException te) {
            return failOrRetry(workflowId, activityName, attempt, options,
                    new ActivityTimeoutException(activityName, timeout), existing.orElse(null));
        } catch (ExecutionException ee) {
            Throwable cause = ee.getCause() != null ? ee.getCause() : ee;
            return failOrRetry(workflowId, activityName, attempt, options, cause, existing.orElse(null));
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return failOrRetry(workflowId, activityName, attempt, options, ie, existing.orElse(null));
        } catch (RuntimeException re) {
            return failOrRetry(workflowId, activityName, attempt, options, re, existing.orElse(null));
        }

        final Object finalResult = result;
        final int finalAttempt = attempt;
        transactionTemplate.executeWithoutResult(s -> {
            ActivityResult ar = existing.orElseGet(ActivityResult::new);
            ar.setWorkflowId(wfId);
            ar.setActivityName(actName);
            ar.setStatus("COMPLETED");
            ar.setResult(serialize(finalResult));
            ar.setResultType(finalResult != null ? finalResult.getClass().getName() : null);
            ar.setError(null);
            ar.setAttempt(finalAttempt);
            if (ar.getCreatedAt() == null) ar.setCreatedAt(Instant.now());
            activityResultRepository.save(ar);
        });

        saveEvent(workflowId, EventType.ACTIVITY_COMPLETED, activityName, attempt, serialize(result));
        log.info("[{}/{}] activity COMPLETED attempt={}", workflowId, activityName, attempt);
        return result;
    }

    private Object failOrRetry(Long workflowId,
                               String activityName,
                               int attempt,
                               ActivityOptions options,
                               Throwable cause,
                               ActivityResult existing) {
        RetryPolicy policy = options.getRetryPolicy() != null ? options.getRetryPolicy() : RetryPolicy.defaultPolicy();
        boolean noRetry = cause instanceof NonRetryableException || policy.isNoRetry(cause);
        boolean exhausted = attempt >= policy.getMaxAttempts();
        boolean willRetry = !noRetry && !exhausted;
        Instant fireAt = willRetry ? Instant.now().plus(policy.nextDelay(attempt)) : null;

        transactionTemplate.executeWithoutResult(s -> {
            ActivityResult ar = existing != null ? existing : new ActivityResult();
            ar.setWorkflowId(workflowId);
            ar.setActivityName(activityName);
            ar.setAttempt(attempt);
            ar.setError(safeMessage(cause));
            ar.setStatus(willRetry ? "FAILED" : "DEAD");
            if (ar.getCreatedAt() == null) ar.setCreatedAt(Instant.now());
            activityResultRepository.save(ar);

            if (willRetry) {
                RetryRecord r = new RetryRecord();
                r.setWorkflowId(workflowId);
                r.setActivityName(activityName);
                r.setAttempt(attempt);
                r.setMaxAttempts(policy.getMaxAttempts());
                r.setFireAt(fireAt);
                r.setReason(safeMessage(cause));
                r.setProcessed(false);
                WorkflowContext ctx = WorkflowContextHolder.current();
                if (ctx instanceof WorkflowContextImpl impl && impl.getCurrentTaskId() != null) {
                    r.setTaskId(impl.getCurrentTaskId());
                }
                retryRepository.save(r);
            }
        });

        if (willRetry) {
            saveEvent(workflowId, EventType.ACTIVITY_RETRYING, activityName, attempt, safeMessage(cause));
            log.warn("[{}/{}] activity FAILED attempt={} — retrying at {}", workflowId, activityName, attempt, fireAt);
        } else {
            saveEvent(workflowId, EventType.ACTIVITY_FAILED, activityName, attempt, safeMessage(cause));
            log.error("[{}/{}] activity FAILED attempt={} — {}",
                    workflowId, activityName, attempt, noRetry ? "non-retryable" : "attempts exhausted");
        }

        throw new ActivityFailureException(activityName, attempt, safeMessage(cause), cause);
    }

    private void saveEvent(Long workflowId, EventType type, String activityName, Integer attempt, String data) {
        transactionTemplate.executeWithoutResult(s -> {
            Event e = new Event();
            e.setWorkflowId(workflowId);
            e.setEventType(type);
            e.setActivityName(activityName);
            e.setAttempt(attempt);
            e.setData(data);
            eventRepository.save(e);
        });
    }

    private String serialize(Object value) {
        if (value == null) return null;
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize activity result", e);
        }
    }

    private Object deserialize(String json, Type targetType) {
        if (json == null) return null;
        if (targetType == void.class || targetType == Void.class) return null;
        if (targetType == null) {
            try {
                return objectMapper.readValue(json, Object.class);
            } catch (Exception e) {
                throw new RuntimeException("Failed to deserialize cached activity result", e);
            }
        }
        try {
            JavaType jt = objectMapper.constructType(targetType);
            return objectMapper.readValue(json, jt);
        } catch (Exception e) {
            throw new RuntimeException("Failed to deserialize cached activity result", e);
        }
    }

    private static String safeMessage(Throwable t) {
        return t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
    }
}
