CREATE TABLE retries (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    task_id       UUID        NOT NULL REFERENCES tasks(id),
    workflow_id   UUID        NOT NULL REFERENCES workflows(id),
    activity_name VARCHAR(255),
    attempt       INT         NOT NULL DEFAULT 0,
    max_attempts  INT         NOT NULL DEFAULT 3,
    fire_at       TIMESTAMPTZ NOT NULL,
    reason        TEXT,
    processed     BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_retries_fire ON retries (fire_at)
    WHERE processed = FALSE;
