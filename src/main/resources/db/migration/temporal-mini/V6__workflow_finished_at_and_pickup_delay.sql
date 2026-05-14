ALTER TABLE wflow.workflow
    ADD COLUMN IF NOT EXISTS finished_at TIMESTAMP;

ALTER TABLE wflow.workflow_history
    ADD COLUMN IF NOT EXISTS pickup_delay_ms BIGINT;
