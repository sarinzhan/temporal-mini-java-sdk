package com.beeline.temporalmini;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Append-only audit record of a single {@link WorkflowEngine#run(Long)} invocation
 * (one scheduler pickup). Inserted at the top of {@code run()} with {@code outcome=null}
 * and updated when {@code run()} returns. Survives {@code engine.restart()} and friends
 * that wipe the live {@code wflow.activity} table.
 */
@Data
@Entity
@Table(name = "workflow_history", schema = "wflow")
public class WorkflowHistoryEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long workflowId;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    /** {@code FINISHED} / {@code RETRY} / {@code FAILED}; {@code null} while the run is in flight. */
    private String outcome;
    /** {@link WorkflowState} of the workflow when this pickup started. */
    private String initialState;
    /** {@code nextRetryAt} the engine set on exit (i.e. when the next pickup is scheduled). */
    private LocalDateTime nextRetryAt;
    @Column(columnDefinition = "text")
    private String errorMessage;
}
