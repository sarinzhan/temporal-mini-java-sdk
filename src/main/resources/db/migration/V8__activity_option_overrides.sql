CREATE TABLE activity_option_overrides (
    activity_name           VARCHAR(255) PRIMARY KEY,
    start_to_close_ms       BIGINT,
    max_attempts            INTEGER,
    initial_interval_ms     BIGINT,
    backoff_coefficient     DOUBLE PRECISION,
    max_interval_ms         BIGINT,
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now()
);
