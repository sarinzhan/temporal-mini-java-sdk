package com.beeline.workflow.persistence.repository;

import com.beeline.workflow.core.model.Task;
import com.beeline.workflow.core.model.TaskStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TaskRepository extends JpaRepository<Task, Long> {

    @Query(value = """
            SELECT * FROM wflow.tasks
            WHERE status = 'PENDING' AND scheduled_at <= now()
            ORDER BY scheduled_at ASC
            LIMIT :batchSize
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<Task> pollPending(@Param("batchSize") int batchSize);

    @Modifying
    @Query(value = """
            UPDATE wflow.tasks
            SET status = 'PENDING', locked_by = NULL, locked_at = NULL, locked_until = NULL,
                lock_token = NULL, version = version + 1
            WHERE status = 'PROCESSING' AND locked_until < now()
            """, nativeQuery = true)
    int resetStaleLocks();

    /**
     * Atomically take ownership of a single PENDING task that {@link #pollPending} has already locked
     * {@code FOR UPDATE} in this transaction. Every lock field is stamped from the DATABASE clock
     * ({@code now()}), so lease expiry never depends on a worker node's wall clock — this removes the
     * premature-reclaim risk under cross-node clock skew. Returns 1 on success, 0 if the row is no
     * longer PENDING.
     */
    @Modifying
    @Query(value = """
            UPDATE wflow.tasks
            SET status = 'PROCESSING', locked_by = :nodeId, locked_at = now(),
                locked_until = now() + make_interval(secs => :ttlSeconds),
                lock_token = :token, version = version + 1
            WHERE id = :id AND status = 'PENDING'
            """, nativeQuery = true)
    int claim(@Param("id") Long id, @Param("nodeId") String nodeId,
              @Param("token") String token, @Param("ttlSeconds") long ttlSeconds);

    /**
     * Extend the lease of a task we still own. Returns 1 if our token still matches (lease kept),
     * 0 if the task was reclaimed by another node (we lost it). The new expiry is computed from the
     * DB clock so renewals stay immune to node clock skew.
     */
    @Modifying
    @Query(value = """
            UPDATE wflow.tasks
            SET locked_until = now() + make_interval(secs => :ttlSeconds), version = version + 1
            WHERE id = :id AND lock_token = :token AND status = 'PROCESSING'
            """, nativeQuery = true)
    int renewLease(@Param("id") Long id, @Param("token") String token, @Param("ttlSeconds") long ttlSeconds);

    /**
     * Finalize a task only if we still own it (fencing). Returns 1 on success, 0 if the lease was
     * lost — in which case the new owner is responsible and we must not overwrite its state.
     */
    @Modifying
    @Query(value = """
            UPDATE wflow.tasks
            SET status = :status, locked_by = NULL, locked_at = NULL, locked_until = NULL,
                lock_token = NULL, version = version + 1
            WHERE id = :id AND lock_token = :token
            """, nativeQuery = true)
    int finalizeIfOwned(@Param("id") Long id, @Param("token") String token, @Param("status") String status);

    @Query(value = """
            SELECT * FROM wflow.tasks
            WHERE status = 'PROCESSING' AND locked_by = :nodeId
            ORDER BY locked_at ASC
            """, nativeQuery = true)
    List<Task> findRunningByNode(@Param("nodeId") String nodeId);

    /**
     * Fencing gate for the whole-turn commit. Locks the task row {@code FOR UPDATE} and returns 1
     * only if we still own it (token matches and still PROCESSING). The committer calls this first
     * inside the commit transaction; if it returns 0 the lease was reclaimed and the commit aborts
     * without writing any events — the new owner is authoritative.
     *
     * <p>The {@code FOR UPDATE} lives in a subquery: PostgreSQL forbids {@code FOR UPDATE} together
     * with an aggregate ({@code count(*)}) in the same query level, so the inner query takes the row
     * lock and the outer query counts the locked rows (0 or 1).
     */
    @Query(value = """
            SELECT count(*) FROM (
                SELECT 1 FROM wflow.tasks
                WHERE id = :id AND lock_token = :token AND status = 'PROCESSING'
                FOR UPDATE
            ) locked
            """, nativeQuery = true)
    int lockIfOwned(@Param("id") Long id, @Param("token") String token);

    List<Task> findByWorkflowIdOrderByCreatedAtDesc(Long workflowId);

    List<Task> findByWorkflowIdAndStatus(Long workflowId, TaskStatus status);
}
