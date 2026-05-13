package com.beeline.temporalmini;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;

/**
 * In-memory tracker for workflows that the engine is currently processing.
 *
 * <p>Tracks two distinct states for the same id:
 * <ul>
 *     <li>{@link Status#SUBMITTED} — the scheduler handed the workflow to the executor
 *     but no worker has picked it up yet (it is sitting in the executor's queue).
 *     This entry exists purely as a deduplication guard so the same id is never
 *     submitted twice across overlapping scheduler polls.</li>
 *     <li>{@link Status#RUNNING} — a worker thread has picked the workflow up and the
 *     engine is actually executing it. This is what the UI shows as the "RUNNING"
 *     virtual state and the metrics sampler exposes as {@code runtimeCount}.</li>
 * </ul>
 *
 * <p>The {@code timestamp} on each entry records when the entry transitioned to its
 * current status: submit-time for {@code SUBMITTED}, pick-up time for {@code RUNNING}.
 */
public class WorkflowRuntimeRegistry {

    public enum Status { SUBMITTED, RUNNING }

    public record Entry(Status status, long sinceEpochMs) {}

    private final ConcurrentMap<Long, Entry> entries = new ConcurrentHashMap<>();

    /**
     * Reserve the id as {@code SUBMITTED}. Used as a dedup guard around
     * {@code executor.execute(...)}.
     *
     * @return {@code true} if this id was newly registered; {@code false} if it was
     *         already present (caller should skip submission).
     */
    public boolean tryStart(Long id) {
        return entries.putIfAbsent(id, new Entry(Status.SUBMITTED, System.currentTimeMillis())) == null;
    }

    /** Flip the entry from {@code SUBMITTED} to {@code RUNNING} as the worker picks it up. */
    public void markRunning(Long id) {
        entries.put(id, new Entry(Status.RUNNING, System.currentTimeMillis()));
    }

    public void finish(Long id) {
        entries.remove(id);
    }

    /** {@code true} only if the workflow is actually executing on a worker right now. */
    public boolean isRunning(Long id) {
        Entry e = entries.get(id);
        return e != null && e.status == Status.RUNNING;
    }

    /** id → epoch-ms when the workflow started running. Only {@code RUNNING} entries. */
    public Map<Long, Long> snapshot() {
        Map<Long, Long> out = new HashMap<>();
        entries.forEach((id, e) -> {
            if (e.status == Status.RUNNING) out.put(id, e.sinceEpochMs);
        });
        return Map.copyOf(out);
    }

    /** Ids that are actually running (worker picked them up). */
    public Set<Long> ids() {
        return entries.entrySet().stream()
                .filter(e -> e.getValue().status == Status.RUNNING)
                .map(Map.Entry::getKey)
                .collect(Collectors.toUnmodifiableSet());
    }

    /** All tracked ids (SUBMITTED ∪ RUNNING). Used only for the dedup guard. */
    public Set<Long> trackedIds() {
        return Collections.unmodifiableSet(entries.keySet());
    }
}
