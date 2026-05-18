package com.beeline.workflow.web.dto;

public record ActivityOverrideDto(
        String activityName,
        Long startToCloseMs,
        Integer maxAttempts,
        Long initialIntervalMs,
        Double backoffCoefficient,
        Long maxIntervalMs
) {}
