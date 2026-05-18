package com.beeline.workflow.web.dto;

import com.beeline.workflow.core.model.WorkflowStatus;

import java.time.Instant;
import java.util.UUID;

public record WorkflowDetailDto(
        UUID id,
        String workflowType,
        WorkflowStatus status,
        Instant startTime,
        Instant endTime,
        Long durationMs,
        String input,
        String result,
        String error
) {}
