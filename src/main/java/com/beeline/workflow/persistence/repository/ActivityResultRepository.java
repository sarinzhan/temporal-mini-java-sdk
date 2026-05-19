package com.beeline.workflow.persistence.repository;

import com.beeline.workflow.core.model.ActivityResult;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ActivityResultRepository extends JpaRepository<ActivityResult, Long> {
    Optional<ActivityResult> findByWorkflowIdAndActivityName(Long workflowId, String activityName);

    List<ActivityResult> findByWorkflowIdOrderByCreatedAtAsc(Long workflowId);
}
