package com.beeline.workflow.core.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@Entity
@Table(name = "instance_registry")
public class InstanceRegistryEntity {

    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "internal_url")
    private String internalUrl;

    @Column(name = "external_url", nullable = false)
    private String externalUrl;

    @Column(name = "last_heartbeat", nullable = false)
    private Instant lastHeartbeat = Instant.now();
}
