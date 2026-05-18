CREATE TABLE events (
    id            BIGSERIAL PRIMARY KEY,
    workflow_id   UUID        NOT NULL REFERENCES workflows(id),
    event_type    VARCHAR(64) NOT NULL,
    activity_name VARCHAR(255),
    attempt       INT,
    data          JSONB,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_events_workflow ON events (workflow_id, created_at);
