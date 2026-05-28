package com.beeline.workflow.persistence.repository;

import com.beeline.workflow.core.model.Event;
import com.beeline.workflow.core.model.EventType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

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
}
