package com.beeline.workflow.persistence.repository;

import com.beeline.workflow.core.model.Signal;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface SignalRepository extends JpaRepository<Signal, UUID> {

    @Query(value = """
            SELECT * FROM signals
            WHERE workflow_id = :workflowId AND signal_name = :signalName AND consumed = FALSE
            ORDER BY created_at ASC
            LIMIT 1
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    Optional<Signal> claimFirstUnconsumed(@Param("workflowId") UUID workflowId,
                                          @Param("signalName") String signalName);

    List<Signal> findByWorkflowIdAndSignalNameOrderByCreatedAtAsc(UUID workflowId, String signalName);
}
