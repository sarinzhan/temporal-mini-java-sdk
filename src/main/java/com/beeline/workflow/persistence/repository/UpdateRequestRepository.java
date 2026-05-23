package com.beeline.workflow.persistence.repository;

import com.beeline.workflow.core.model.UpdateRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UpdateRequestRepository extends JpaRepository<UpdateRequest, Long> {

    Optional<UpdateRequest> findByUpdateId(String updateId);

    List<UpdateRequest> findByWorkflowIdAndStatusOrderByCreatedAtAsc(Long workflowId, String status);
}
