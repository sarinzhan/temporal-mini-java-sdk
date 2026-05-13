package com.beeline.temporalmini;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ActivityHistoryRepository extends JpaRepository<ActivityHistoryEntity, Long> {
    List<ActivityHistoryEntity> findByWorkflowHistoryIdOrderByStartedAtAsc(Long workflowHistoryId);
    List<ActivityHistoryEntity> findByWorkflowIdOrderByStartedAtAsc(Long workflowId);
}
