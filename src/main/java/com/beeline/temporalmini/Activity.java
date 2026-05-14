package com.beeline.temporalmini;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "activity", schema = "wflow")
public class Activity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long workflowId;
    private String name;
    private int attempt;
    private boolean success;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    @Column(columnDefinition = "text")
    private String inputPayload;
    @Column(columnDefinition = "text")
    private String outputPayload;
    /** Fully-qualified class name of the deserialized {@code outputPayload}; used by replay to reconstruct the typed result. */
    private String outputType;
    private String errorMessage;
}
