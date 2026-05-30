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
    completed_at  TIMESTAMPTZ,
    version       BIGINT       NOT NULL DEFAULT 0   -- @Version optimistic-lock fence
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
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version      BIGINT       NOT NULL DEFAULT 0   -- @Version optimistic-lock fence
);

CREATE INDEX idx_tasks_poll ON wflow.tasks (status, scheduled_at)
    WHERE status = 'PENDING';

-- Workflow history — single source of truth for replay.
CREATE TABLE wflow.events (
    id            BIGSERIAL   PRIMARY KEY,
    workflow_id   BIGINT      NOT NULL REFERENCES wflow.workflows(id),
    event_type    VARCHAR(64) NOT NULL,
    command_type  VARCHAR(32),                -- ACTIVITY / SIDE_EFFECT / VERSION / null
    seq           INT,                        -- monotonic per-workflow command counter; null for non-command events
    activity_name VARCHAR(255),
    payload       JSONB,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_events_replay ON wflow.events (workflow_id, id);
CREATE INDEX idx_events_version ON wflow.events (workflow_id, event_type)
    WHERE event_type = 'VERSION_MARKER';

-- Guard against duplicate command outcomes: a given (workflow, seq, event_type) command event may
-- appear at most once. Activities legitimately record multiple rows per seq across attempts
-- (STARTED/RETRY_SCHEDULED), so this uniqueness only covers the *terminal/marker* event types —
-- the ones replay treats as authoritative. A second writer (e.g. a duplicated turn after a crash)
-- trying to re-record a completion fails the INSERT instead of corrupting history.
CREATE UNIQUE INDEX uq_events_terminal ON wflow.events (workflow_id, seq, event_type)
    WHERE seq IS NOT NULL AND event_type IN
        ('ACTIVITY_COMPLETED', 'ACTIVITY_FAILED', 'ACTIVITY_TIMEOUT',
         'SIDE_EFFECT_RECORDED', 'VERSION_MARKER');

-- A workflow has at most one terminal lifecycle event.
CREATE UNIQUE INDEX uq_events_workflow_terminal ON wflow.events (workflow_id, event_type)
    WHERE event_type IN ('WORKFLOW_COMPLETED', 'WORKFLOW_FAILED');

-- Schedule of future workflow wake-ups (e.g. activity retry backoff). Source of truth stays in
-- events; a row here only controls *when* a parked workflow is re-enqueued.
CREATE TABLE wflow.schedule (
    id          BIGSERIAL   PRIMARY KEY,
    workflow_id BIGINT      NOT NULL REFERENCES wflow.workflows(id),
    seq         INT,
    fire_at     TIMESTAMPTZ NOT NULL,
    reason      TEXT,
    processed   BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_schedule_fire ON wflow.schedule (fire_at)
    WHERE processed = FALSE;

-- Multi-instance registry (only used when workflow.instance.external-url is set).
CREATE TABLE wflow.instance_registry (
    id             VARCHAR(255) PRIMARY KEY,
    internal_url   VARCHAR(512),
    external_url   VARCHAR(512) NOT NULL,
    last_heartbeat TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_instance_registry_heartbeat ON wflow.instance_registry (last_heartbeat);
