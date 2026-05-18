CREATE TABLE activity_results (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workflow_id   UUID         NOT NULL REFERENCES workflows(id),
    activity_name VARCHAR(255) NOT NULL,
    status        VARCHAR(32)  NOT NULL,
    result        JSONB,
    error         TEXT,
    attempt       INT          NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (workflow_id, activity_name)
);
