package com.beeline.workflow.web.controller;

import com.beeline.workflow.core.model.InstanceRegistryEntity;
import com.beeline.workflow.core.model.MetricsSnapshot;
import com.beeline.workflow.core.model.TaskStatus;
import com.beeline.workflow.engine.cluster.InstanceRegistryService;
import com.beeline.workflow.persistence.repository.InstanceRegistryRepository;
import com.beeline.workflow.persistence.repository.MetricsSnapshotRepository;
import com.beeline.workflow.persistence.repository.TaskRepository;
import com.beeline.workflow.spring.autoconfigure.WorkflowProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Read-only monitoring surface for the UI: time-bucketed metric series (from the
 * {@code wflow.metrics_snapshot} rollup) and the live worker/instance list.
 */
@RestController
@RequestMapping("/workflow/api")
public class MetricsController {

    private static final int MAX_BUCKETS = 240;
    private static final Duration DEFAULT_RANGE = Duration.ofHours(24);

    private final MetricsSnapshotRepository snapshotRepository;
    private final InstanceRegistryRepository instanceRepository;
    private final TaskRepository taskRepository;
    private final WorkflowProperties properties;

    public MetricsController(MetricsSnapshotRepository snapshotRepository,
                             InstanceRegistryRepository instanceRepository,
                             TaskRepository taskRepository,
                             WorkflowProperties properties) {
        this.snapshotRepository = snapshotRepository;
        this.instanceRepository = instanceRepository;
        this.taskRepository = taskRepository;
        this.properties = properties;
    }

    /**
     * Aggregate snapshots in {@code [from, to]} (epoch millis; defaults to the last 24h) into
     * {@code buckets} evenly spaced points. Counter columns are summed per bucket; gauge columns
     * take the last value seen in the bucket (instantaneous state).
     */
    @GetMapping("/metrics")
    public MetricsResponse metrics(@RequestParam(required = false) Long from,
                                   @RequestParam(required = false) Long to,
                                   @RequestParam(defaultValue = "48") int buckets) {
        int n = Math.min(Math.max(buckets, 1), MAX_BUCKETS);
        Instant hiI = to != null ? Instant.ofEpochMilli(to) : Instant.now();
        Instant loI = from != null ? Instant.ofEpochMilli(from) : hiI.minus(DEFAULT_RANGE);

        long lo = loI.toEpochMilli();
        long hi = hiI.toEpochMilli();
        if (hi <= lo) {
            hi = lo + 1;
        }
        double bw = (double) (hi - lo) / n;

        int[] created = new int[n], started = new int[n], completed = new int[n], failed = new int[n];
        int[] running = new int[n], executing = new int[n], queue = new int[n], schedule = new int[n];
        double[] durSum = new double[n];
        int[] durCount = new int[n];

        List<MetricsSnapshot> snapshots = snapshotRepository.findInRange(loI, hiI);
        long totCreated = 0, totStarted = 0, totCompleted = 0, totFailed = 0;
        double totDurSum = 0;
        int totDurCount = 0;
        for (MetricsSnapshot s : snapshots) {
            int i = (int) ((s.getCapturedAt().toEpochMilli() - lo) / bw);
            if (i < 0 || i >= n) {
                continue;
            }
            created[i] += s.getCreated();
            started[i] += s.getStarted();
            completed[i] += s.getCompleted();
            failed[i] += s.getFailed();
            // gauges: last snapshot in the bucket wins (snapshots are ordered ascending)
            running[i] = s.getRunning();
            executing[i] = s.getExecuting();
            queue[i] = s.getQueue();
            schedule[i] = s.getSchedulePending();
            if (s.getAvgDurationMs() != null) {
                durSum[i] += s.getAvgDurationMs();
                durCount[i]++;
                totDurSum += s.getAvgDurationMs();
                totDurCount++;
            }
            totCreated += s.getCreated();
            totStarted += s.getStarted();
            totCompleted += s.getCompleted();
            totFailed += s.getFailed();
        }

        Integer[] successRate = new Integer[n];
        double[] avgDur = new double[n];
        for (int i = 0; i < n; i++) {
            int tot = completed[i] + failed[i];
            successRate[i] = tot > 0 ? Math.round((completed[i] * 100f) / tot) : null;
            avgDur[i] = durCount[i] > 0 ? durSum[i] / durCount[i] : 0;
        }

        MetricsSnapshot latest = snapshots.isEmpty() ? null : snapshots.get(snapshots.size() - 1);
        long totDecided = totCompleted + totFailed;
        Cur cur = new Cur(
                (int) totCreated,
                latest != null ? latest.getExecuting() : 0,
                latest != null ? latest.getSchedulePending() : 0,
                latest != null ? latest.getQueue() : 0,
                latest != null ? latest.getRunning() : 0,
                totDurCount > 0 ? totDurSum / totDurCount : 0,
                totDecided > 0 ? Math.round((totCompleted * 100f) / totDecided) : 100,
                (int) totStarted,
                (int) totCompleted,
                (int) totFailed);

        return new MetricsResponse(lo, hi, bw, n, created, started, completed, failed,
                running, executing, queue, schedule, successRate, avgDur, cur);
    }

    /**
     * Live workers. In multi-instance mode these are the registered nodes with a fresh heartbeat;
     * in single-instance mode the registry is empty, so we synthesize one entry for the local node
     * (its in-flight turns are every PROCESSING task).
     */
    @GetMapping("/workers")
    public List<WorkerView> workers() {
        int pollRate = pollsPerSecond();
        Instant staleSince = Instant.now().minus(InstanceRegistryService.STALE_THRESHOLD);
        List<InstanceRegistryEntity> live = instanceRepository.findLive(staleSince);
        if (!live.isEmpty()) {
            List<WorkerView> out = new ArrayList<>(live.size());
            for (InstanceRegistryEntity e : live) {
                int active = taskRepository.findRunningByNode(e.getId()).size();
                out.add(new WorkerView(e.getId(),
                        e.getExternalUrl() != null ? e.getExternalUrl() : e.getId(),
                        List.of(), "ALIVE", active, properties.getWorkerPoolSize(),
                        pollRate, e.getLastHeartbeat()));
            }
            return out;
        }
        // single-instance: no registry rows, report the local node
        int active = (int) taskRepository.countByStatus(TaskStatus.PROCESSING);
        return List.of(new WorkerView(properties.getInstanceId(), properties.getInstanceId(),
                List.of(), "ALIVE", active, properties.getWorkerPoolSize(), pollRate, Instant.now()));
    }

    /** Approximate poll frequency of a single worker thread, derived from the poll interval. */
    private int pollsPerSecond() {
        long interval = properties.getPollIntervalMs();
        return interval > 0 ? (int) Math.round(1000.0 / interval) : 0;
    }

    public record MetricsResponse(long lo, long hi, double bw, int nBuckets,
                                  int[] created, int[] started, int[] completed, int[] failed,
                                  int[] running, int[] executing, int[] queue, int[] schedule,
                                  Integer[] successRate, double[] avgDur, Cur cur) {}

    public record Cur(int created, int executing, int schedule, int queue, int running,
                      double avgDurationMs, int successPct, int started, int completed, int failed) {}

    public record WorkerView(String id, String host, List<String> taskQueues, String status,
                             int activeTasks, int capacity, int pollRate, Instant lastHeartbeat) {}
}
