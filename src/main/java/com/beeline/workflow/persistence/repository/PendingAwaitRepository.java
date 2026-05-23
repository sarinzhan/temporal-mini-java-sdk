package com.beeline.workflow.persistence.repository;

import com.beeline.workflow.core.model.PendingAwait;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface PendingAwaitRepository extends JpaRepository<PendingAwait, Long> {

    @Query(value = """
            SELECT * FROM wflow.pending_awaits
            WHERE deadline IS NOT NULL AND deadline <= :now
            ORDER BY deadline ASC
            LIMIT :batchSize
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<PendingAwait> pollDueByDeadline(@Param("now") Instant now, @Param("batchSize") int batchSize);

    List<PendingAwait> findByWorkflowId(Long workflowId);

    Optional<PendingAwait> findByWorkflowIdAndSeq(Long workflowId, Integer seq);
}
