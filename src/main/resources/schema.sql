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
    lock_token   VARCHAR(64),                -- fencing token: unique per claim, checked before every write
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_tasks_poll ON wflow.tasks (status, scheduled_at)
    WHERE status = 'PENDING';

-- Workflow history — single source of truth for replay.
CREATE TABLE wflow.events (
    id            BIGSERIAL   PRIMARY KEY,
    workflow_id   BIGINT      NOT NULL REFERENCES wflow.workflows(id),
    event_type    VARCHAR(64) NOT NULL,
    command_type  VARCHAR(32),                -- ACTIVITY / TIMER / AWAIT / SIDE_EFFECT / VERSION / UPDATE / null
    seq           INT,                        -- monotonic per-workflow command counter; null for non-command events
    activity_name VARCHAR(255),
    payload       JSONB,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_events_replay ON wflow.events (workflow_id, id);
CREATE INDEX idx_events_version ON wflow.events (workflow_id, event_type)
    WHERE event_type = 'VERSION_MARKER';

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

-- Index for pending timers (sleep). Source of truth = events; this row is dropped on TIMER_FIRED.
CREATE TABLE wflow.pending_timers (
    id          BIGSERIAL   PRIMARY KEY,
    workflow_id BIGINT      NOT NULL REFERENCES wflow.workflows(id),
    seq         INT         NOT NULL,
    fire_at     TIMESTAMPTZ NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (workflow_id, seq)
);

CREATE INDEX idx_pending_timers_fire ON wflow.pending_timers (fire_at);

-- Index for pending awaits. Same idea: source of truth = events; row dropped on AWAIT_FIRED.
CREATE TABLE wflow.pending_awaits (
    id          BIGSERIAL   PRIMARY KEY,
    workflow_id BIGINT      NOT NULL REFERENCES wflow.workflows(id),
    seq         INT         NOT NULL,
    deadline    TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (workflow_id, seq)
);

CREATE INDEX idx_pending_awaits_deadline ON wflow.pending_awaits (deadline);
CREATE INDEX idx_pending_awaits_workflow ON wflow.pending_awaits (workflow_id);

CREATE TABLE wflow.signals (
    id          BIGSERIAL    PRIMARY KEY,
    workflow_id BIGINT       NOT NULL REFERENCES wflow.workflows(id),
    signal_name VARCHAR(255) NOT NULL,
    payload     JSONB,
    consumed    BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_signals_lookup ON wflow.signals (workflow_id, signal_name, consumed);

-- Pending update requests — HTTP futures live in memory, but the request itself is persisted.
CREATE TABLE wflow.update_requests (
    id           BIGSERIAL    PRIMARY KEY,
    update_id    VARCHAR(64)  NOT NULL UNIQUE,
    workflow_id  BIGINT       NOT NULL REFERENCES wflow.workflows(id),
    method_name  VARCHAR(255) NOT NULL,
    args_payload JSONB,
    status       VARCHAR(32)  NOT NULL DEFAULT 'PENDING',  -- PENDING / COMPLETED / FAILED
    result       JSONB,
    error        TEXT,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    completed_at TIMESTAMPTZ
);

CREATE INDEX idx_update_requests_workflow ON wflow.update_requests (workflow_id, status);

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
