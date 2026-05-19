-- Beeline Workflow Engine — schema
-- Run manually before starting the application.

CREATE SCHEMA IF NOT EXISTS wflow;

CREATE TABLE wflow.workflows (
    id            BIGSERIAL    PRIMARY KEY,
    workflow_type VARCHAR(255) NOT NULL,
    status        VARCHAR(32)  NOT NULL DEFAULT 'PENDING',
    input         JSONB,
    result        JSONB,
    error         TEXT,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    completed_at  TIMESTAMPTZ
);

CREATE TABLE wflow.tasks (
    id           BIGSERIAL    PRIMARY KEY,
    workflow_id  BIGINT       NOT NULL REFERENCES wflow.workflows(id),
    task_type    VARCHAR(255) NOT NULL,
    status       VARCHAR(32)  NOT NULL DEFAULT 'PENDING',
    payload      JSONB,
    scheduled_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    locked_by    VARCHAR(255),
    locked_until TIMESTAMPTZ,
    locked_at    TIMESTAMPTZ,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_tasks_poll ON wflow.tasks (status, scheduled_at)
    WHERE status = 'PENDING';

CREATE TABLE wflow.events (
    id            BIGSERIAL PRIMARY KEY,
    workflow_id   BIGINT      NOT NULL REFERENCES wflow.workflows(id),
    event_type    VARCHAR(64) NOT NULL,
    activity_name VARCHAR(255),
    attempt       INT,
    data          TEXT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_events_workflow ON wflow.events (workflow_id, created_at);

CREATE TABLE wflow.activity_results (
    id            BIGSERIAL    PRIMARY KEY,
    workflow_id   BIGINT       NOT NULL REFERENCES wflow.workflows(id),
    activity_name VARCHAR(255) NOT NULL,
    status        VARCHAR(32)  NOT NULL,
    result        JSONB,
    error         TEXT,
    attempt       INT          NOT NULL DEFAULT 0,
    result_type   VARCHAR(512),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (workflow_id, activity_name)
);

CREATE TABLE wflow.retries (
    id            BIGSERIAL   PRIMARY KEY,
    task_id       BIGINT      NOT NULL REFERENCES wflow.tasks(id),
    workflow_id   BIGINT      NOT NULL REFERENCES wflow.workflows(id),
    activity_name VARCHAR(255),
    attempt       INT         NOT NULL DEFAULT 0,
    max_attempts  INT         NOT NULL DEFAULT 3,
    fire_at       TIMESTAMPTZ NOT NULL,
    reason        TEXT,
    processed     BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_retries_fire ON wflow.retries (fire_at)
    WHERE processed = FALSE;

CREATE TABLE wflow.signals (
    id          BIGSERIAL    PRIMARY KEY,
    workflow_id BIGINT       NOT NULL REFERENCES wflow.workflows(id),
    signal_name VARCHAR(255) NOT NULL,
    payload     JSONB,
    consumed    BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_signals_lookup ON wflow.signals (workflow_id, signal_name, consumed);

CREATE TABLE wflow.activity_option_overrides (
    activity_name       VARCHAR(255) PRIMARY KEY,
    start_to_close_ms   BIGINT,
    max_attempts        INTEGER,
    initial_interval_ms BIGINT,
    backoff_coefficient DOUBLE PRECISION,
    max_interval_ms     BIGINT,
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE wflow.instance_registry (
    id             VARCHAR(255) PRIMARY KEY,
    internal_url   VARCHAR(512),
    external_url   VARCHAR(512) NOT NULL,
    last_heartbeat TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_instance_registry_heartbeat ON wflow.instance_registry (last_heartbeat);
