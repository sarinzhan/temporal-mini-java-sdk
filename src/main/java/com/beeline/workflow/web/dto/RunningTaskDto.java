package com.beeline.workflow.web.dto;

import java.time.Instant;
import java.util.UUID;

public record RunningTaskDto(
        UUID taskId,
        UUID workflowId,
        Instant lockedAt,
        Instant lockedUntil
) {}
