package com.beeline.workflow.web.dto;

import com.beeline.workflow.core.model.WorkflowStatus;

import java.time.Instant;
import java.util.UUID;

public record WorkflowSummaryDto(
        UUID id,
        String workflowType,
        WorkflowStatus status,
        Instant startTime,
        Instant endTime,
        Long durationMs
) {}
