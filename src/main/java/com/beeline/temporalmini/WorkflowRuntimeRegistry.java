package com.beeline.temporalmini;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * In-memory tracker for workflows that the engine is currently processing.
 *
 * <p>The "RUNNING" view in the UI is derived from this registry rather than a persistent
 * column on {@code wflow.workflow}. A workflow is registered as soon as the scheduler
 * submits it to the executor and de-registered when the run completes (success, failure,
 * or scheduled-for-retry). The registry also serves as a deduplication guard so the
 * same workflow id is never queued twice across overlapping polls.
 */
public class WorkflowRuntimeRegistry {

    private final ConcurrentMap<Long, Long> running = new ConcurrentHashMap<>();

    /**
     * @return {@code true} if this id was newly registered; {@code false} if it was
     *         already present (caller should skip submission).
     */
    public boolean tryStart(Long id) {
        return running.putIfAbsent(id, System.currentTimeMillis()) == null;
    }

    public void finish(Long id) {
        running.remove(id);
    }

    public boolean isRunning(Long id) {
        return running.containsKey(id);
    }

    /** id → epoch-ms when the workflow was submitted. Snapshot copy. */
    public Map<Long, Long> snapshot() {
        return Map.copyOf(running);
    }

    public Set<Long> ids() {
        return Collections.unmodifiableSet(running.keySet());
    }
}
