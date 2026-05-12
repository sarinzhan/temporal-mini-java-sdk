CREATE TABLE IF NOT EXISTS wflow.metric_sample
(
    ts             TIMESTAMP NOT NULL PRIMARY KEY,
    pool_active    INT       NOT NULL,
    pool_free      INT       NOT NULL,
    pool_queue     INT       NOT NULL,
    runtime_count  INT       NOT NULL,
    cnt_new        BIGINT    NOT NULL,
    cnt_runnable   BIGINT    NOT NULL,
    cnt_blocked    BIGINT    NOT NULL,
    cnt_finished   BIGINT    NOT NULL,
    cnt_failed     BIGINT    NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_metric_sample_ts
    ON wflow.metric_sample (ts DESC);
