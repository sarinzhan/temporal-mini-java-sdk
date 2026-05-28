package com.beeline.workflow.core.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * Schedule of future workflow task launches. The activity executor writes a row here (with the
 * retry {@code fireAt}) when a failed-but-retryable activity parks the workflow; the
 * {@code WakeupScheduler} polls due rows and enqueues a {@code workflow} task so the parked
 * workflow re-runs and performs the next attempt. Source of truth for replay stays in
 * {@code wflow.events}; this table only drives <i>when</i> a parked workflow wakes up.
 */
@Getter
@Setter
@Entity
@Table(name = "schedule", schema = "wflow")
public class Schedule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "workflow_id", nullable = false)
    private Long workflowId;

    /** The command seq this wakeup is for (e.g. the retrying activity); informational. */
    @Column(name = "seq")
    private Integer seq;

    @Column(name = "fire_at", nullable = false)
    private Instant fireAt;

    @Column(name = "reason", columnDefinition = "text")
    private String reason;

    @Column(name = "processed", nullable = false)
    private boolean processed = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}
