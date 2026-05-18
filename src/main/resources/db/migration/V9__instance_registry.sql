CREATE TABLE instance_registry (
    id             VARCHAR(255) PRIMARY KEY,
    internal_url   VARCHAR(512),
    external_url   VARCHAR(512) NOT NULL,
    last_heartbeat TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_instance_registry_heartbeat ON instance_registry (last_heartbeat);
