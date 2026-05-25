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
            SET status = 'PENDING', locked_by = NULL, locked_at = NULL, locked_until = NULL, lock_token = NULL
            WHERE status = 'PROCESSING' AND locked_until < now()
            """, nativeQuery = true)
    int resetStaleLocks();

    /**
     * Extend the lease of a task we still own. Returns 1 if our token still matches (lease kept),
     * 0 if the task was reclaimed by another node (we lost it).
     */
    @Modifying
    @Query(value = """
            UPDATE wflow.tasks
            SET locked_until = :until
            WHERE id = :id AND lock_token = :token AND status = 'PROCESSING'
            """, nativeQuery = true)
    int renewLease(@Param("id") Long id, @Param("token") String token, @Param("until") java.time.Instant until);

    /**
     * Finalize a task only if we still own it (fencing). Returns 1 on success, 0 if the lease was
     * lost — in which case the new owner is responsible and we must not overwrite its state.
     */
    @Modifying
    @Query(value = """
            UPDATE wflow.tasks
            SET status = :status, locked_by = NULL, locked_at = NULL, locked_until = NULL, lock_token = NULL
            WHERE id = :id AND lock_token = :token
            """, nativeQuery = true)
    int finalizeIfOwned(@Param("id") Long id, @Param("token") String token, @Param("status") String status);

    @Query(value = """
            SELECT * FROM wflow.tasks
            WHERE status = 'PROCESSING' AND locked_by = :nodeId
            ORDER BY locked_at ASC
            """, nativeQuery = true)
    List<Task> findRunningByNode(@Param("nodeId") String nodeId);

    List<Task> findByWorkflowIdOrderByCreatedAtDesc(Long workflowId);

    List<Task> findByWorkflowIdAndStatus(Long workflowId, TaskStatus status);
}
