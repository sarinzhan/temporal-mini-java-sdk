package com.beeline.workflow.persistence.repository;

import com.beeline.workflow.core.model.Schedule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public interface ScheduleRepository extends JpaRepository<Schedule, Long> {

    @Query(value = """
            SELECT * FROM wflow.schedule
            WHERE processed = FALSE AND fire_at <= :now
            ORDER BY fire_at ASC
            LIMIT :batchSize
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<Schedule> pollDue(@Param("now") Instant now, @Param("batchSize") int batchSize);

    List<Schedule> findByWorkflowIdAndProcessedFalseOrderByFireAtAsc(Long workflowId);

    /** Future wake-ups not yet fired — workflows currently parked in a retry/timer backoff. */
    long countByProcessedFalse();
}
