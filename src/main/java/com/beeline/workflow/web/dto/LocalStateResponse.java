package com.beeline.workflow.web.dto;

import java.util.List;

public record LocalStateResponse(
        String nodeId,
        PoolStats pool,
        List<RunningTaskDto> running
) {}
