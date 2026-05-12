package com.beeline.temporalmini;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Persists {@link WorkflowState} as the legacy string values. Specifically, {@code STOPPED}
 * is stored as {@code "BLOCKED"} on disk so that an existing database does not need a
 * data migration when the SDK upgrades. All other values map 1:1 to their enum names.
 *
 * <p>JPQL queries continue to reference {@code WorkflowState.STOPPED} normally — the JPA
 * provider invokes this converter both directions automatically.
 */
@Converter(autoApply = false)
public class WorkflowStateConverter implements AttributeConverter<WorkflowState, String> {

    @Override
    public String convertToDatabaseColumn(WorkflowState attribute) {
        if (attribute == null) return null;
        return attribute == WorkflowState.STOPPED ? "BLOCKED" : attribute.name();
    }

    @Override
    public WorkflowState convertToEntityAttribute(String dbData) {
        if (dbData == null) return null;
        if ("BLOCKED".equals(dbData)) return WorkflowState.STOPPED;
        // legacy rows that were persisted as RUNNABLE before the RETRY rename
        if ("RUNNABLE".equals(dbData)) return WorkflowState.RETRY;
        return WorkflowState.valueOf(dbData);
    }
}
