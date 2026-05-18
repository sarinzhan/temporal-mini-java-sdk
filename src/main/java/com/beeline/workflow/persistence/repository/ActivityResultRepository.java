package com.beeline.workflow.persistence.repository;

import com.beeline.workflow.core.model.ActivityResult;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ActivityResultRepository extends JpaRepository<ActivityResult, UUID> {
    Optional<ActivityResult> findByWorkflowIdAndActivityName(UUID workflowId, String activityName);

    List<ActivityResult> findByWorkflowIdOrderByCreatedAtAsc(UUID workflowId);
}
