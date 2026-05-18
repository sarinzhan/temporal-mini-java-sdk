package com.beeline.workflow.web.dto;

import java.time.Instant;

public record PendingActivityDto(
        String activityName,
        int attempt,
        int maxAttempts,
        Instant nextFireAt,
        String lastError,
        String status
) {}
