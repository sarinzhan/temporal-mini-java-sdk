package com.beeline.workflow.engine.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Thin Micrometer facade. All Micrometer types are confined to this class so the rest of the
 * engine (notably {@link MetricsCollector}) never references them — that keeps the metrics
 * collector usable even when micrometer is not on the classpath. When no {@link MeterRegistry}
 * bean is available the facade degrades to a no-op and only the DB snapshot rollup runs.
 *
 * <p>Gauges are backed by holders updated each collection interval; counters are advanced by the
 * per-interval delta the collector already computed from the events log (which is replay-safe —
 * each terminal event is recorded once), so Prometheus sees the same numbers the UI reads from
 * {@code wflow.metrics_snapshot}.
 */
public class WorkflowMetrics {

    private final boolean enabled;

    private final AtomicInteger running = new AtomicInteger();
    private final AtomicInteger queue = new AtomicInteger();
    private final AtomicInteger executing = new AtomicInteger();
    private final AtomicInteger schedulePending = new AtomicInteger();

    private final Counter created;
    private final Counter started;
    private final Counter completed;
    private final Counter failed;

    public WorkflowMetrics(MeterRegistry registry) {
        this.enabled = registry != null;
        if (!enabled) {
            this.created = this.started = this.completed = this.failed = null;
            return;
        }
        Gauge.builder("workflow.running", running, AtomicInteger::get)
                .description("Workflows currently in RUNNING state").register(registry);
        Gauge.builder("workflow.queue", queue, AtomicInteger::get)
                .description("Tasks waiting for a worker (PENDING)").register(registry);
        Gauge.builder("workflow.executing", executing, AtomicInteger::get)
                .description("Worker turns in flight (tasks PROCESSING)").register(registry);
        Gauge.builder("workflow.schedule.pending", schedulePending, AtomicInteger::get)
                .description("Future wake-ups not yet fired (retry/timer backoff)").register(registry);

        this.created = Counter.builder("workflow.created.total")
                .description("Workflows created").register(registry);
        this.started = Counter.builder("workflow.activity.started.total")
                .description("Activity executions started").register(registry);
        this.completed = Counter.builder("workflow.activity.completed.total")
                .description("Activity executions completed").register(registry);
        this.failed = Counter.builder("workflow.activity.failed.total")
                .description("Activity executions failed or timed out").register(registry);
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** Set the instantaneous gauge values for this interval. */
    public void updateGauges(int running, int queue, int executing, int schedulePending) {
        this.running.set(running);
        this.queue.set(queue);
        this.executing.set(executing);
        this.schedulePending.set(schedulePending);
    }

    /** Advance the cumulative counters by the deltas observed in this interval. */
    public void recordDeltas(int created, int started, int completed, int failed) {
        if (!enabled) {
            return;
        }
        if (created > 0) this.created.increment(created);
        if (started > 0) this.started.increment(started);
        if (completed > 0) this.completed.increment(completed);
        if (failed > 0) this.failed.increment(failed);
    }
}
