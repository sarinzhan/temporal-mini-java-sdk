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
           "com.beeline.temporalmini.WorkflowState.RETRY) " +
           "AND (w.nextRetryAt IS NULL OR w.nextRetryAt <= :now)")
    List<WorkflowEntity> findPendingWorkflows(@Param("now") LocalDateTime now);

    @Query("SELECT COUNT(w) FROM WorkflowEntity w WHERE w.state IN " +
           "(com.beeline.temporalmini.WorkflowState.NEW, " +
           "com.beeline.temporalmini.WorkflowState.RETRY) " +
           "AND (w.nextRetryAt IS NULL OR w.nextRetryAt <= :now)")
    long countQueued(@Param("now") LocalDateTime now);

    @Query("SELECT COUNT(w) FROM WorkflowEntity w WHERE w.state IN " +
           "(com.beeline.temporalmini.WorkflowState.NEW, " +
           "com.beeline.temporalmini.WorkflowState.RETRY) " +
           "AND (w.nextRetryAt IS NULL OR w.nextRetryAt <= :now) " +
           "AND w.id NOT IN :excludeIds")
    long countQueuedExcluding(@Param("now") LocalDateTime now,
                              @Param("excludeIds") Collection<Long> excludeIds);

    @Query("SELECT COUNT(w) FROM WorkflowEntity w WHERE w.state = " +
           "com.beeline.temporalmini.WorkflowState.RETRY " +
           "AND w.nextRetryAt > :now")
    long countWaiting(@Param("now") LocalDateTime now);

    /** RETRY rows where nextRetryAt <= now — ready for pickup (IN_QUEUE view). */
    @Query("SELECT w FROM WorkflowEntity w WHERE " +
           "(w.state = com.beeline.temporalmini.WorkflowState.NEW OR " +
           "(w.state = com.beeline.temporalmini.WorkflowState.RETRY AND (w.nextRetryAt IS NULL OR w.nextRetryAt <= :now)))")
    Page<WorkflowEntity> findInQueue(@Param("now") LocalDateTime now, Pageable pageable);

    /** Like {@link #findInQueue} but excludes ids currently being executed (registry). */
    @Query("SELECT w FROM WorkflowEntity w WHERE " +
           "(w.state = com.beeline.temporalmini.WorkflowState.NEW OR " +
           "(w.state = com.beeline.temporalmini.WorkflowState.RETRY AND (w.nextRetryAt IS NULL OR w.nextRetryAt <= :now))) " +
           "AND w.id NOT IN :excludeIds")
    Page<WorkflowEntity> findInQueueExcluding(@Param("now") LocalDateTime now,
                                              @Param("excludeIds") Collection<Long> excludeIds,
                                              Pageable pageable);

    /** RETRY rows where nextRetryAt > now — sleeping until retry window (WAITING view). */
    @Query("SELECT w FROM WorkflowEntity w WHERE " +
           "w.state = com.beeline.temporalmini.WorkflowState.RETRY AND w.nextRetryAt > :now")
    Page<WorkflowEntity> findWaiting(@Param("now") LocalDateTime now, Pageable pageable);

    Page<WorkflowEntity> findByState(WorkflowState state, Pageable pageable);

    Page<WorkflowEntity> findByStateIn(Collection<WorkflowState> states, Pageable pageable);

    Page<WorkflowEntity> findByIdIn(Collection<Long> ids, Pageable pageable);

    long countByState(WorkflowState state);

    /**
     * Bulk selector by creation window. Used by {@code /workflows/bulk/*} endpoints
     * to expand a {@code {from, to, states}} filter into a list of ids before
     * applying the action. {@code states} is optional via the JPQL conditional.
     */
    @Query("""
            SELECT w.id FROM WorkflowEntity w
            WHERE w.createdAt BETWEEN :from AND :to
              AND (:#{#states == null || #states.isEmpty()} = TRUE OR w.state IN :states)
            """)
    List<Long> findIdsByCreatedAtRange(@Param("from") LocalDateTime from,
                                       @Param("to")   LocalDateTime to,
                                       @Param("states") Collection<WorkflowState> states);
}
