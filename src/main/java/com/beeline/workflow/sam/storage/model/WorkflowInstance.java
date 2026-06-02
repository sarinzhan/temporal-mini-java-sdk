package com.beeline.workflow.sam.storage.model;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.UUID;


@Data
@Entity
public class WorkflowInstance {

    @Id
    private UUID id;
    private String classType;
    private String name;

    private String inputPayload;
    private String inputType;
    private String outputPayload;
    private String outputType;

    private OffsetDateTime createdAt;

    private WorkflowStatus status;
}
