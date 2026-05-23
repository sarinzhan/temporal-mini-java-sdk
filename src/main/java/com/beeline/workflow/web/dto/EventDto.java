package com.beeline.workflow.web.dto;

import com.beeline.workflow.core.model.EventType;

import java.time.Instant;

public record EventDto(
        Long id,
        EventType eventType,
        String commandType,
        Integer seq,
        String activityName,
        String payload,
        Instant createdAt
) {}
