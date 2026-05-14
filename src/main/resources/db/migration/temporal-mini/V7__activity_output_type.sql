ALTER TABLE wflow.activity
    ADD COLUMN IF NOT EXISTS output_type VARCHAR(512);

ALTER TABLE wflow.activity_history
    ADD COLUMN IF NOT EXISTS output_type VARCHAR(512);
