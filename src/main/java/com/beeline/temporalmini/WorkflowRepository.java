package com.beeline.temporalmini;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

public interface WorkflowRepository extends JpaRepository<WorkflowEntity, Long> {

    @Query("SELECT w FROM WorkflowEntity w WHERE w.state IN " +
           "(com.beeline.temporalmini.WorkflowState.NEW, " +
           "com.beeline.temporalmini.WorkflowState.RUNNABLE) " +
           "AND (w.nextRetryAt IS NULL OR w.nextRetryAt <= :now)")
    List<WorkflowEntity> findPendingWorkflows(@Param("now") LocalDateTime now);

    Page<WorkflowEntity> findByState(WorkflowState state, Pageable pageable);

    Page<WorkflowEntity> findByStateIn(Collection<WorkflowState> states, Pageable pageable);

    long countByState(WorkflowState state);
}
