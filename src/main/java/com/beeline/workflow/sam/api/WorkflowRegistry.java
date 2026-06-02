package com.beeline.workflow.sam.api;

import com.beeline.workflow.sam.api.annotation.WorkflowInterface;

public interface WorkflowRegistry {
    void register(Class<?> clazz, Class<?> interfaze);

    WorkflowInterface<?, ?> get(String type);
}
