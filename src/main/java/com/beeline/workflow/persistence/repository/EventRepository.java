package com.beeline.workflow.persistence.repository;

import com.beeline.workflow.core.model.Event;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface EventRepository extends JpaRepository<Event, Long> {
    List<Event> findByWorkflowIdOrderByCreatedAtAsc(Long workflowId);
}
