package com.beeline.workflow.persistence.repository;

import com.beeline.workflow.core.model.WorkflowInstance;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface WorkflowRepository extends JpaRepository<WorkflowInstance, Long>,
        JpaSpecificationExecutor<WorkflowInstance> {

    @Query("SELECT DISTINCT w.workflowType FROM WorkflowInstance w ORDER BY w.workflowType")
    List<String> findAllWorkflowTypes();
}
