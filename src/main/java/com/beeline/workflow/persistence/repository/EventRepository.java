package com.beeline.workflow.persistence.repository;

import com.beeline.workflow.core.model.Event;
import com.beeline.workflow.core.model.EventType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface EventRepository extends JpaRepository<Event, Long> {

    /** History in append order. id is monotonic and BIGSERIAL — good enough for replay. */
    List<Event> findByWorkflowIdOrderByIdAsc(Long workflowId);

    @Query("""
            SELECT e FROM Event e
            WHERE e.workflowId = :workflowId AND e.eventType = :type
            ORDER BY e.id ASC
            """)
    List<Event> findByWorkflowIdAndType(@Param("workflowId") Long workflowId,
                                        @Param("type") EventType type);

    @Query("""
            SELECT e FROM Event e
            WHERE e.workflowId = :workflowId AND e.eventType = 'VERSION_MARKER'
            """)
    List<Event> findVersionMarkers(@Param("workflowId") Long workflowId);

    /**
     * Locate the latest UPDATE_REQUESTED event whose updateId matches and which has no
     * corresponding UPDATE_COMPLETED event. Used by the worker to find the next pending update.
     */
    @Query(value = """
            SELECT * FROM wflow.events e
            WHERE e.workflow_id = :workflowId
              AND e.event_type = 'UPDATE_REQUESTED'
              AND NOT EXISTS (
                  SELECT 1 FROM wflow.events c
                  WHERE c.workflow_id = :workflowId
                    AND c.event_type = 'UPDATE_COMPLETED'
                    AND c.payload->>'updateId' = e.payload->>'updateId'
              )
            ORDER BY e.id ASC
            LIMIT 1
            """, nativeQuery = true)
    Optional<Event> findOldestPendingUpdate(@Param("workflowId") Long workflowId);
}
