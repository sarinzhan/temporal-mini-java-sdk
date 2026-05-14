package com.beeline.temporalmini;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Append-only audit record of a single activity attempt. Mirror of {@link Activity}
 * with FKs to the wrapping {@link WorkflowHistoryEntity} and to the live activity row.
 *
 * <p>{@code activityId} is nullable: {@code engine.restart()} and
 * {@code engine.restartFromActivity()} delete rows from {@code wflow.activity}; the
 * corresponding {@code activity_id} here is set to {@code NULL} by an ON DELETE SET NULL
 * FK so the history record survives. The {@code workflowId} / {@code workflowHistoryId}
 * links stay intact for navigation.
 */
@Data
@Entity
@Table(name = "activity_history", schema = "wflow")
public class ActivityHistoryEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long workflowHistoryId;
    private Long workflowId;
    private Long activityId;
    private String name;
    private int attempt;
    private boolean success;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    @Column(columnDefinition = "text")
    private String inputPayload;
    @Column(columnDefinition = "text")
    private String outputPayload;
    /** Fully-qualified class name of the deserialized {@code outputPayload}. */
    private String outputType;
    private String errorMessage;
}
