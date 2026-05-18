package com.beeline.workflow.persistence.repository;

import com.beeline.workflow.core.model.WorkflowInstance;
import com.beeline.workflow.core.model.WorkflowStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface WorkflowRepository extends JpaRepository<WorkflowInstance, UUID> {

    @Query("""
            SELECT w FROM WorkflowInstance w
            WHERE (:hasStatuses = false OR w.status IN :statuses)
              AND (:workflowType IS NULL OR w.workflowType = :workflowType)
              AND (:idText IS NULL OR CAST(w.id AS string) LIKE CONCAT('%', :idText, '%'))
              AND (:from IS NULL OR w.createdAt >= :from)
              AND (:to IS NULL OR w.createdAt <= :to)
            """)
    Page<WorkflowInstance> search(@Param("hasStatuses") boolean hasStatuses,
                                  @Param("statuses") List<WorkflowStatus> statuses,
                                  @Param("workflowType") String workflowType,
                                  @Param("idText") String idText,
                                  @Param("from") Instant from,
                                  @Param("to") Instant to,
                                  Pageable pageable);

    @Query("SELECT DISTINCT w.workflowType FROM WorkflowInstance w ORDER BY w.workflowType")
    List<String> findAllWorkflowTypes();
}
