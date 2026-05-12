package com.beeline.temporalmini;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * One snapshot of the engine's runtime numbers, taken on a fixed cadence by
 * {@link MetricsSampler}. Designed to be cheap to insert and easy to aggregate
 * with Postgres {@code date_trunc(..., ts)} for time-series charts.
 *
 * <p>The state counts ({@code cntNew}…{@code cntFailed}) are <b>cumulative</b>
 * snapshots — the entire row count for that state at sample time. Throughput is
 * not stored: callers compute it client-side as the delta between two samples
 * of {@code cntFinished} / {@code cntFailed}.
 */
@Data
@Entity
@Table(name = "metric_sample", schema = "wflow")
public class MetricSample {

    @Id
    private LocalDateTime ts;

    private int poolActive;
    private int poolFree;
    private int poolQueue;
    private int runtimeCount;

    private long cntNew;
    private long cntRetry;
    private long cntBlocked;
    private long cntFinished;
    private long cntFailed;
}
