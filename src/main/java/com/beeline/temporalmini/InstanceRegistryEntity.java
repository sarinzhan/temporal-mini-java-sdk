package com.beeline.temporalmini;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "instance_registry", schema = "wflow")
public class InstanceRegistryEntity {

    @Id
    private String id;

    @Column(nullable = false, length = 512)
    private String url;

    @Column(nullable = false)
    private LocalDateTime lastHeartbeat;
}
