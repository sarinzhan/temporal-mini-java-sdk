package com.beeline.workflow.sam.storage.api;

import com.beeline.workflow.sam.core.engine.WorkflowEngine;
import com.beeline.workflow.sam.storage.model.WorkflowInstance;

import java.util.Optional;

public interface WorkflowIntanceRepository {
    Optional<WorkflowInstance> findById(String id);
}
