package com.beeline.temporalmini;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface WorkflowHistoryRepository extends JpaRepository<WorkflowHistoryEntity, Long> {
    List<WorkflowHistoryEntity> findByWorkflowIdOrderByStartedAtAsc(Long workflowId);
}
