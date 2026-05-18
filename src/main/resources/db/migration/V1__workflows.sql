CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE workflows (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workflow_type VARCHAR(255) NOT NULL,
    status        VARCHAR(32)  NOT NULL DEFAULT 'PENDING',
    input         JSONB,
    result        JSONB,
    error         TEXT,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    completed_at  TIMESTAMPTZ
);
