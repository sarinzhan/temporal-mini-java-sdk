package com.beeline.workflow.persistence.repository;

import com.beeline.workflow.core.model.WorkflowInstance;
import com.beeline.workflow.core.model.WorkflowStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public interface WorkflowRepository extends JpaRepository<WorkflowInstance, Long>,
        JpaSpecificationExecutor<WorkflowInstance> {

    @Query("SELECT DISTINCT w.workflowType FROM WorkflowInstance w ORDER BY w.workflowType")
    List<String> findAllWorkflowTypes();

    long countByStatus(WorkflowStatus status);

    /** Average wall-clock duration (ms) of workflows that completed within the window; null if none. */
    @Query(value = """
            SELECT AVG(EXTRACT(EPOCH FROM (completed_at - created_at)) * 1000)
            FROM wflow.workflows
            WHERE status = 'COMPLETED' AND completed_at > :from AND completed_at <= :to
            """, nativeQuery = true)
    Double avgCompletedDurationMs(@Param("from") Instant from, @Param("to") Instant to);
}
