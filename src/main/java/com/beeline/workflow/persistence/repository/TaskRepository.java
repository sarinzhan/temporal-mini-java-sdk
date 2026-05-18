package com.beeline.workflow.persistence.repository;

import com.beeline.workflow.core.model.Task;
import com.beeline.workflow.core.model.TaskStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface TaskRepository extends JpaRepository<Task, UUID> {

    @Query(value = """
            SELECT * FROM tasks
            WHERE status = 'PENDING' AND scheduled_at <= now()
            ORDER BY scheduled_at ASC
            LIMIT :batchSize
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<Task> pollPending(@Param("batchSize") int batchSize);

    @Modifying
    @Query(value = """
            UPDATE tasks
            SET status = 'PENDING', locked_by = NULL, locked_at = NULL, locked_until = NULL
            WHERE status = 'PROCESSING' AND locked_until < now()
            """, nativeQuery = true)
    int resetStaleLocks();

    @Query(value = """
            SELECT * FROM tasks
            WHERE status = 'PROCESSING' AND locked_by = :nodeId
            ORDER BY locked_at ASC
            """, nativeQuery = true)
    List<Task> findRunningByNode(@Param("nodeId") String nodeId);

    List<Task> findByWorkflowIdOrderByCreatedAtDesc(UUID workflowId);

    List<Task> findByWorkflowIdAndStatus(UUID workflowId, TaskStatus status);
}
