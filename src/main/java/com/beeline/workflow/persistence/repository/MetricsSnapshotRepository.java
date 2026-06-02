package com.beeline.workflow.persistence.repository;

import com.beeline.workflow.core.model.MetricsSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public interface MetricsSnapshotRepository extends JpaRepository<MetricsSnapshot, Long> {

    @Query("""
            SELECT m FROM MetricsSnapshot m
            WHERE m.capturedAt >= :from AND m.capturedAt <= :to
            ORDER BY m.capturedAt ASC
            """)
    List<MetricsSnapshot> findInRange(@Param("from") Instant from, @Param("to") Instant to);

    MetricsSnapshot findFirstByOrderByCapturedAtDesc();

    @Modifying
    @Query("DELETE FROM MetricsSnapshot m WHERE m.capturedAt < :cutoff")
    int deleteOlderThan(@Param("cutoff") Instant cutoff);
}
