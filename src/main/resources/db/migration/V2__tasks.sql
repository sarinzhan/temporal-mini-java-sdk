CREATE TABLE tasks (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workflow_id  UUID         NOT NULL REFERENCES workflows(id),
    task_type    VARCHAR(255) NOT NULL,
    status       VARCHAR(32)  NOT NULL DEFAULT 'PENDING',
    payload      JSONB,
    scheduled_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    locked_by    VARCHAR(255),
    locked_until TIMESTAMPTZ,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_tasks_poll ON tasks (status, scheduled_at)
    WHERE status = 'PENDING';
