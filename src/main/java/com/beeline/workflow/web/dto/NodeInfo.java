package com.beeline.workflow.web.dto;

import java.time.Instant;

public record NodeInfo(
        String id,
        String internalUrl,
        String externalUrl,
        Instant lastHeartbeat,
        boolean self
) {}
