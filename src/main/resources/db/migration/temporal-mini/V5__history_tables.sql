CREATE TABLE IF NOT EXISTS wflow.workflow_history
(
    id            BIGSERIAL PRIMARY KEY,
    workflow_id   BIGINT       NOT NULL REFERENCES wflow.workflow (id),
    started_at    TIMESTAMP    NOT NULL,
    finished_at   TIMESTAMP,
    outcome       VARCHAR(50),
    initial_state VARCHAR(50)  NOT NULL,
    next_retry_at TIMESTAMP,
    error_message TEXT
);

CREATE TABLE IF NOT EXISTS wflow.activity_history
(
    id                  BIGSERIAL PRIMARY KEY,
    workflow_history_id BIGINT       NOT NULL REFERENCES wflow.workflow_history (id),
    workflow_id         BIGINT       NOT NULL REFERENCES wflow.workflow (id),
    activity_id         BIGINT                REFERENCES wflow.activity (id) ON DELETE SET NULL,
    name                VARCHAR(255) NOT NULL,
    attempt             INT          NOT NULL,
    success             BOOLEAN      NOT NULL,
    started_at          TIMESTAMP,
    finished_at         TIMESTAMP,
    input_payload       TEXT,
    output_payload      TEXT,
    error_message       TEXT
);

CREATE INDEX IF NOT EXISTS idx_workflow_history_workflow_id_started
    ON wflow.workflow_history (workflow_id, started_at);

CREATE INDEX IF NOT EXISTS idx_activity_history_workflow_history_id
    ON wflow.activity_history (workflow_history_id);

CREATE INDEX IF NOT EXISTS idx_activity_history_workflow_id_name
    ON wflow.activity_history (workflow_id, name);

CREATE INDEX IF NOT EXISTS idx_activity_history_activity_id
    ON wflow.activity_history (activity_id);
