package com.beeline.temporalmini.ui;

/**
 * Snapshot of the workflow executor's thread pool — surfaced on {@code GET /pool}
 * so the UI can show how many workers are busy vs. idle and whether the queue is filling up.
 */
public record PoolStats(
        int active,
        int free,
        int poolSize,
        int corePoolSize,
        int maxPoolSize,
        int queue,
        int queueCapacity
) {
}
