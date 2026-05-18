package com.beeline.workflow.web.dto;

import com.beeline.workflow.core.model.EventType;

import java.time.Instant;

public record EventDto(
        Long id,
        EventType eventType,
        String activityName,
        Integer attempt,
        String data,
        Instant createdAt
) {}
