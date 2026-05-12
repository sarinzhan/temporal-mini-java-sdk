package com.beeline.temporalmini;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

public interface MetricSampleRepository extends JpaRepository<MetricSample, LocalDateTime> {

    /** Raw rows (no aggregation) — used for narrow time windows. */
    List<MetricSample> findByTsBetweenOrderByTsAsc(LocalDateTime from, LocalDateTime to);

    /**
     * Bucket aggregation — Postgres {@code date_trunc(unit, ts)} over a window.
     * Pool gauges use {@code AVG}, queue uses {@code MAX} (peak depth in the bucket
     * is what operators care about), state counters use {@code MAX} (last value
     * per bucket is the closest proxy to "value at end of bucket" in monotonic
     * counters; {@code AVG} of cumulative gauges is misleading).
     *
     * <p>Returned shape is {@code Object[]} positional: [ts, poolActive, poolFree,
     * poolQueue, runtimeCount, cntNew, cntRetry, cntBlocked, cntFinished, cntFailed].
     */
    @Query(value = """
            SELECT date_trunc(:unit, ts)         AS bucket_ts,
                   AVG(pool_active)::INT         AS pool_active,
                   AVG(pool_free)::INT           AS pool_free,
                   MAX(pool_queue)::INT          AS pool_queue,
                   MAX(runtime_count)::INT       AS runtime_count,
                   MAX(cnt_new)                  AS cnt_new,
                   MAX(cnt_retry)                AS cnt_retry,
                   MAX(cnt_blocked)              AS cnt_blocked,
                   MAX(cnt_finished)             AS cnt_finished,
                   MAX(cnt_failed)               AS cnt_failed
              FROM wflow.metric_sample
             WHERE ts BETWEEN :from AND :to
             GROUP BY bucket_ts
             ORDER BY bucket_ts
            """, nativeQuery = true)
    List<Object[]> findBucketedRaw(@Param("unit") String unit,
                                   @Param("from") LocalDateTime from,
                                   @Param("to")   LocalDateTime to);

    @Modifying
    @Transactional
    @Query("DELETE FROM MetricSample m WHERE m.ts < :cutoff")
    int deleteOlderThan(@Param("cutoff") LocalDateTime cutoff);
}
