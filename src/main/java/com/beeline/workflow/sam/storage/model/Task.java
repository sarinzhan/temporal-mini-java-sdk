package com.beeline.workflow.sam.storage.model;


import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Data
public class Task {
    @Id
    private Long id;

    private UUID workflowInstanceId;

    @Enumerated(EnumType.STRING)
    private TaskStatus taskStatus;

    private OffsetDateTime createdAt;

    private OffsetDateTime takeAt;

    private OffsetDateTime lockedUntil;

    private UUID lockKey;
}
