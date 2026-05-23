package com.beeline.workflow.persistence.repository;

import com.beeline.workflow.core.model.PendingTimer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface PendingTimerRepository extends JpaRepository<PendingTimer, Long> {

    @Query(value = """
            SELECT * FROM wflow.pending_timers
            WHERE fire_at <= :now
            ORDER BY fire_at ASC
            LIMIT :batchSize
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<PendingTimer> pollDue(@Param("now") Instant now, @Param("batchSize") int batchSize);

    Optional<PendingTimer> findByWorkflowIdAndSeq(Long workflowId, Integer seq);
}
