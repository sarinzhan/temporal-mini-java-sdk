package com.beeline.workflow.web.dto;

import java.time.Instant;

public record RunningTaskDto(
        Long taskId,
        Long workflowId,
        Instant lockedAt,
        Instant lockedUntil
) {}
