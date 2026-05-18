package com.beeline.workflow.persistence.repository;

import com.beeline.workflow.core.model.RetryRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface RetryRepository extends JpaRepository<RetryRecord, UUID> {

    @Query(value = """
            SELECT * FROM retries
            WHERE processed = FALSE AND fire_at <= :now
            ORDER BY fire_at ASC
            LIMIT :batchSize
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<RetryRecord> pollDue(@Param("now") Instant now, @Param("batchSize") int batchSize);

    List<RetryRecord> findByWorkflowIdAndProcessedFalseOrderByFireAtAsc(UUID workflowId);
}
