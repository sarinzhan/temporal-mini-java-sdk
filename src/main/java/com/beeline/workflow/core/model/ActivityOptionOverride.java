package com.beeline.workflow.core.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@Entity
@Table(name = "activity_option_overrides")
public class ActivityOptionOverride {

    @Id
    @Column(name = "activity_name", nullable = false, length = 255)
    private String activityName;

    @Column(name = "start_to_close_ms")
    private Long startToCloseMs;

    @Column(name = "max_attempts")
    private Integer maxAttempts;

    @Column(name = "initial_interval_ms")
    private Long initialIntervalMs;

    @Column(name = "backoff_coefficient")
    private Double backoffCoefficient;

    @Column(name = "max_interval_ms")
    private Long maxIntervalMs;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @PreUpdate
    void touch() { this.updatedAt = Instant.now(); }
}