package com.beeline.workflow.core.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * A single rollup row written by the metrics collector each interval. Counter columns
 * ({@code created/started/completed/failed}) are per-interval deltas; gauge columns
 * ({@code running/queue/executing/schedulePending}) are the instantaneous state at
 * {@link #capturedAt}. See {@code V2__metrics_snapshot.sql}.
 */
@Getter
@Setter
@Entity
@Table(name = "metrics_snapshot", schema = "wflow")
public class MetricsSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "captured_at", nullable = false)
    private Instant capturedAt = Instant.now();

    @Column(name = "window_ms", nullable = false)
    private long windowMs;

    @Column(name = "created", nullable = false)
    private int created;

    @Column(name = "started", nullable = false)
    private int started;

    @Column(name = "completed", nullable = false)
    private int completed;

    @Column(name = "failed", nullable = false)
    private int failed;

    @Column(name = "running", nullable = false)
    private int running;

    @Column(name = "queue", nullable = false)
    private int queue;

    @Column(name = "executing", nullable = false)
    private int executing;

    @Column(name = "schedule_pending", nullable = false)
    private int schedulePending;

    @Column(name = "avg_duration_ms")
    private Double avgDurationMs;

    @Column(name = "success_pct")
    private Integer successPct;
}
