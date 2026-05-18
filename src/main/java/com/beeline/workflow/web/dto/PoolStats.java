package com.beeline.workflow.web.dto;

public record PoolStats(
        int active,
        int queue,
        int max
) {}
