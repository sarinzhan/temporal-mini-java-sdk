CREATE TABLE wflow.instance_registry (
    id             VARCHAR(255) PRIMARY KEY,
    url            VARCHAR(512) NOT NULL,
    last_heartbeat TIMESTAMP    NOT NULL
);
