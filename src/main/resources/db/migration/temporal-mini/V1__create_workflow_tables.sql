CREATE SCHEMA IF NOT EXISTS wflow;

CREATE TABLE IF NOT EXISTS wflow.workflow
(
    id            BIGSERIAL PRIMARY KEY,
    workflow_type VARCHAR(255),
    state         VARCHAR(50)  NOT NULL,
    next_payload  TEXT,
    created_at    TIMESTAMP,
    started_at    TIMESTAMP,
    next_retry_at TIMESTAMP,
    error_message TEXT
);

CREATE TABLE IF NOT EXISTS wflow.activity
(
    id             BIGSERIAL PRIMARY KEY,
    workflow_id    BIGINT       NOT NULL REFERENCES wflow.workflow (id),
    name           VARCHAR(255) NOT NULL,
    attempt        INT          NOT NULL,
    success        BOOLEAN      NOT NULL,
    started_at     TIMESTAMP,
    finished_at    TIMESTAMP,
    input_payload  TEXT,
    output_payload TEXT,
    error_message  TEXT
);

CREATE INDEX IF NOT EXISTS idx_workflow_state_retry
    ON wflow.workflow (state, next_retry_at);

CREATE INDEX IF NOT EXISTS idx_activity_workflow_name
    ON wflow.activity (workflow_id, name);
