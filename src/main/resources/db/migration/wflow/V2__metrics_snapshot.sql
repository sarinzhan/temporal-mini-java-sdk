-- Periodic rollup of engine activity, written by MetricsCollector on a fixed schedule.
-- The UI reads time-bucketed series from this table; Prometheus (if the host wires a
-- MeterRegistry) gets the same numbers live via Micrometer. Source of truth for workflow
-- state stays in wflow.events / wflow.workflows — this table is a derived, lossy rollup
-- kept only for cheap charting and history that survives restarts.

CREATE TABLE wflow.metrics_snapshot (
    id               BIGSERIAL    PRIMARY KEY,
    captured_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    -- length of the interval this row covers (captured_at - previous capture)
    window_ms        BIGINT       NOT NULL,

    -- counters: events that occurred within this interval (deltas, not cumulative)
    created          INT          NOT NULL DEFAULT 0,  -- WORKFLOW_CREATED
    started          INT          NOT NULL DEFAULT 0,  -- ACTIVITY_STARTED
    completed        INT          NOT NULL DEFAULT 0,  -- ACTIVITY_COMPLETED
    failed           INT          NOT NULL DEFAULT 0,  -- ACTIVITY_FAILED + ACTIVITY_TIMEOUT

    -- gauges: instantaneous state at captured_at
    running          INT          NOT NULL DEFAULT 0,  -- workflows in RUNNING
    queue            INT          NOT NULL DEFAULT 0,  -- tasks PENDING (waiting for a worker)
    executing        INT          NOT NULL DEFAULT 0,  -- tasks PROCESSING (turn in flight)
    schedule_pending INT          NOT NULL DEFAULT 0,  -- unprocessed future wake-ups

    -- derived rates over the interval
    avg_duration_ms  DOUBLE PRECISION,                 -- avg duration of workflows completed in window
    success_pct      INT                               -- completed / (completed + failed) * 100
);

CREATE INDEX idx_metrics_snapshot_captured_at ON wflow.metrics_snapshot (captured_at);
