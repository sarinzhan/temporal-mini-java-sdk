package com.beeline.temporalmini.metrics;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * In-memory fallback registry used when no external Micrometer backend
 * (Prometheus, Graphite, etc.) is present on the classpath.
 *
 * <p>Wraps {@link SimpleMeterRegistry} and exposes {@link #snapshot()} so the
 * UI can read recorded timings via {@code GET /temporal-mini/api/metrics/micrometer}.
 */
public class LocalMeterRegistry extends SimpleMeterRegistry {

    public record TimerSnapshot(
            String name,
            Map<String, String> tags,
            long count,
            double totalMs,
            double meanMs,
            double max
    ) {}

    /** Returns all registered {@link Timer} meters as a flat list of snapshots. */
    public List<TimerSnapshot> snapshot() {
        return getMeters().stream()
                .filter(m -> m instanceof Timer)
                .map(m -> {
                    Timer t = (Timer) m;
                    Map<String, String> tags = new LinkedHashMap<>();
                    for (var tag : m.getId().getTags()) {
                        tags.put(tag.getKey(), tag.getValue());
                    }
                    return new TimerSnapshot(
                            m.getId().getName(),
                            tags,
                            t.count(),
                            t.totalTime(TimeUnit.MILLISECONDS),
                            t.mean(TimeUnit.MILLISECONDS),
                            t.max(TimeUnit.MILLISECONDS)
                    );
                })
                .toList();
    }
}
