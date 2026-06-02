package com.beeline.workflow.sam.storage.model;

import java.time.OffsetDateTime;
import java.util.UUID;

public class WorkflowInstance {
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
