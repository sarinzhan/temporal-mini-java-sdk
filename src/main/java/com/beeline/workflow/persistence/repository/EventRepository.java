package com.beeline.workflow.persistence.repository;

import com.beeline.workflow.core.model.Event;
import com.beeline.workflow.core.model.EventType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
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

    /**
     * Count events grouped by type within {@code (from, to]} — one query feeds all the per-interval
     * counters in a metrics snapshot. Returns rows of {@code [EventType, Long count]}.
     */
    @Query("""
            SELECT e.eventType, COUNT(e) FROM Event e
            WHERE e.createdAt > :from AND e.createdAt <= :to
            GROUP BY e.eventType
            """)
    List<Object[]> countByTypeBetween(@Param("from") Instant from, @Param("to") Instant to);
}
