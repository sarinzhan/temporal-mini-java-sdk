package com.beeline.workflow.engine.metrics;

import com.beeline.workflow.core.model.EventType;
import com.beeline.workflow.core.model.MetricsSnapshot;
import com.beeline.workflow.core.model.TaskStatus;
import com.beeline.workflow.core.model.WorkflowStatus;
import com.beeline.workflow.persistence.repository.EventRepository;
import com.beeline.workflow.persistence.repository.MetricsSnapshotRepository;
import com.beeline.workflow.persistence.repository.ScheduleRepository;
import com.beeline.workflow.persistence.repository.TaskRepository;
import com.beeline.workflow.persistence.repository.WorkflowRepository;
import com.beeline.workflow.spring.autoconfigure.WorkflowProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Periodically rolls up engine activity into a {@link MetricsSnapshot} row and feeds the same
 * numbers to Micrometer (if a registry is present). Counters are derived from the events log over
 * the interval {@code (lastCapture, now]}; gauges are read live from the workflow/task/schedule
 * tables. This keeps the rollup replay-safe and avoids threading metric calls through the engine's
 * hot path — events are the single source of truth and we only read them.
 */
public class MetricsCollector {

    private static final Logger log = LoggerFactory.getLogger(MetricsCollector.class);

    private final WorkflowRepository workflowRepository;
    private final TaskRepository taskRepository;
    private final ScheduleRepository scheduleRepository;
    private final EventRepository eventRepository;
    private final MetricsSnapshotRepository snapshotRepository;
    private final WorkflowMetrics metrics;
    private final WorkflowProperties properties;

    /** Upper bound of the previously captured interval; events after this are not yet counted. */
    private volatile Instant lastCapture;

    public MetricsCollector(WorkflowRepository workflowRepository,
                            TaskRepository taskRepository,
                            ScheduleRepository scheduleRepository,
                            EventRepository eventRepository,
                            MetricsSnapshotRepository snapshotRepository,
                            WorkflowMetrics metrics,
                            WorkflowProperties properties) {
        this.workflowRepository = workflowRepository;
        this.taskRepository = taskRepository;
        this.scheduleRepository = scheduleRepository;
        this.eventRepository = eventRepository;
        this.snapshotRepository = snapshotRepository;
        this.metrics = metrics;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${workflow.metrics.interval-ms:15000}")
    @Transactional
    public void collect() {
        Instant now = Instant.now();
        Instant from = resolveWindowStart(now);

        // --- per-interval counters from the events log ---
        int created = 0, started = 0, completed = 0, failed = 0;
        for (Object[] row : eventRepository.countByTypeBetween(from, now)) {
            EventType type = (EventType) row[0];
            int count = ((Number) row[1]).intValue();
            switch (type) {
                case WORKFLOW_CREATED -> created += count;
                case ACTIVITY_STARTED -> started += count;
                case ACTIVITY_COMPLETED -> completed += count;
                case ACTIVITY_FAILED, ACTIVITY_TIMEOUT -> failed += count;
                default -> { /* not tracked */ }
            }
        }

        // --- instantaneous gauges from live state ---
        int running = (int) workflowRepository.countByStatus(WorkflowStatus.RUNNING);
        int queue = (int) taskRepository.countByStatus(TaskStatus.PENDING);
        int executing = (int) taskRepository.countByStatus(TaskStatus.PROCESSING);
        int schedulePending = (int) scheduleRepository.countByProcessedFalse();

        Double avgDurationMs = workflowRepository.avgCompletedDurationMs(from, now);
        Integer successPct = (completed + failed) > 0
                ? Math.round((completed * 100f) / (completed + failed))
                : null;

        MetricsSnapshot snapshot = new MetricsSnapshot();
        snapshot.setCapturedAt(now);
        snapshot.setWindowMs(Duration.between(from, now).toMillis());
        snapshot.setCreated(created);
        snapshot.setStarted(started);
        snapshot.setCompleted(completed);
        snapshot.setFailed(failed);
        snapshot.setRunning(running);
        snapshot.setQueue(queue);
        snapshot.setExecuting(executing);
        snapshot.setSchedulePending(schedulePending);
        snapshot.setAvgDurationMs(avgDurationMs);
        snapshot.setSuccessPct(successPct);
        snapshotRepository.save(snapshot);

        metrics.updateGauges(running, queue, executing, schedulePending);
        metrics.recordDeltas(created, started, completed, failed);

        prune(now);
        lastCapture = now;
    }

    /**
     * Start of the window to count over. On the first run after startup we bridge from the most
     * recent persisted snapshot (so a restart does not drop or double-count events, since the
     * window is time-bounded); if none exists we fall back to a single interval.
     */
    private Instant resolveWindowStart(Instant now) {
        if (lastCapture != null) {
            return lastCapture;
        }
        MetricsSnapshot last = snapshotRepository.findFirstByOrderByCapturedAtDesc();
        if (last != null && last.getCapturedAt().isBefore(now)) {
            return last.getCapturedAt();
        }
        return now.minusMillis(properties.getMetrics().getIntervalMs());
    }

    private void prune(Instant now) {
        long retentionHours = properties.getMetrics().getRetentionHours();
        if (retentionHours <= 0) {
            return;
        }
        int removed = snapshotRepository.deleteOlderThan(now.minus(Duration.ofHours(retentionHours)));
        if (removed > 0 && log.isDebugEnabled()) {
            log.debug("Metrics: pruned {} snapshot(s) older than {}h", removed, retentionHours);
        }
    }
}
