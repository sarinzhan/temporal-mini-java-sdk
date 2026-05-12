package com.beeline.temporalmini;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "workflow", schema = "wflow")
public class WorkflowEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String workflowType;
    @Convert(converter = WorkflowStateConverter.class)
    private WorkflowState state;
    @Column(columnDefinition = "text")
    private String nextPayload;
    private LocalDateTime createdAt;
    private LocalDateTime startedAt;
    private LocalDateTime nextRetryAt;
    @Column(columnDefinition = "text")
    private String errorMessage;
}
