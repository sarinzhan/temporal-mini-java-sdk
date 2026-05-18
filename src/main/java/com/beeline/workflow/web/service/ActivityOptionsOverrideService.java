package com.beeline.workflow.web.service;

import com.beeline.workflow.core.config.ActivityOptions;
import com.beeline.workflow.core.config.RetryPolicy;
import com.beeline.workflow.core.model.ActivityOptionOverride;
import com.beeline.workflow.persistence.repository.ActivityOptionOverrideRepository;
import com.beeline.workflow.web.dto.ActivityOverrideDto;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public class ActivityOptionsOverrideService {

    private final ActivityOptionOverrideRepository repository;
    private final ConcurrentHashMap<String, ActivityOptionOverride> cache = new ConcurrentHashMap<>();

    public ActivityOptionsOverrideService(ActivityOptionOverrideRepository repository) {
        this.repository = repository;
        // Lazily populated on read; nothing to prefetch.
    }

    /** Merge any runtime override on top of the code-supplied options. */
    public ActivityOptions resolve(String activityName, ActivityOptions base) {
        ActivityOptionOverride o = cache.computeIfAbsent(activityName,
                name -> repository.findById(name).orElse(null));
        if (o == null) return base;

        Duration timeout = o.getStartToCloseMs() != null
                ? Duration.ofMillis(o.getStartToCloseMs())
                : base.getStartToCloseTimeout();
        RetryPolicy basePolicy = base.getRetryPolicy() != null
                ? base.getRetryPolicy() : RetryPolicy.defaultPolicy();
        int maxAttempts = o.getMaxAttempts() != null
                ? o.getMaxAttempts() : basePolicy.getMaxAttempts();
        Duration initial = o.getInitialIntervalMs() != null
                ? Duration.ofMillis(o.getInitialIntervalMs())
                : basePolicy.getInitialInterval();
        double backoff = o.getBackoffCoefficient() != null
                ? o.getBackoffCoefficient() : basePolicy.getBackoffCoefficient();

        RetryPolicy.Builder pb = RetryPolicy.newBuilder()
                .setMaxAttempts(maxAttempts)
                .setInitialInterval(initial)
                .setBackoffCoefficient(backoff);
        for (Class<? extends Throwable> c : basePolicy.getNoRetryOn()) pb.addNoRetry(c);
        RetryPolicy mergedPolicy = pb.build();

        return ActivityOptions.newBuilder()
                .setStartToCloseTimeout(timeout)
                .setRetryPolicy(mergedPolicy)
                .setIdempotencyKey(base.getIdempotencyKey())
                .setSignalName(base.getSignalName())
                .build();
    }

    public List<ActivityOverrideDto> list() {
        return repository.findAll().stream().map(ActivityOptionsOverrideService::toDto).toList();
    }

    public Optional<ActivityOverrideDto> get(String activityName) {
        return repository.findById(activityName).map(ActivityOptionsOverrideService::toDto);
    }

    public ActivityOverrideDto save(ActivityOverrideDto dto) {
        if (dto.activityName() == null || dto.activityName().isBlank()) {
            throw new IllegalArgumentException("activityName is required");
        }
        ActivityOptionOverride o = repository.findById(dto.activityName())
                .orElseGet(() -> {
                    ActivityOptionOverride fresh = new ActivityOptionOverride();
                    fresh.setActivityName(dto.activityName());
                    return fresh;
                });
        o.setStartToCloseMs(dto.startToCloseMs());
        o.setMaxAttempts(dto.maxAttempts());
        o.setInitialIntervalMs(dto.initialIntervalMs());
        o.setBackoffCoefficient(dto.backoffCoefficient());
        o.setMaxIntervalMs(dto.maxIntervalMs());
        repository.save(o);
        cache.put(dto.activityName(), o);
        return toDto(o);
    }

    public void delete(String activityName) {
        repository.deleteById(activityName);
        cache.remove(activityName);
    }

    private static ActivityOverrideDto toDto(ActivityOptionOverride o) {
        return new ActivityOverrideDto(
                o.getActivityName(),
                o.getStartToCloseMs(),
                o.getMaxAttempts(),
                o.getInitialIntervalMs(),
                o.getBackoffCoefficient(),
                o.getMaxIntervalMs());
    }
}
