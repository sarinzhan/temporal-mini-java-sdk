package com.beeline.workflow.sam.storage.model;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Data
public class Schedule {

    @Id
    private Long id;

    private UUID workflowId;

    private OffsetDateTime createdAt;

    private OffsetDateTime fireAt;

    private String comment;
}
