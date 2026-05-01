package com.beeline.temporalmini;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ActivityRepository extends JpaRepository<Activity, Long> {

    @Query("SELECT a FROM Activity a WHERE a.workflowId = :workflowId AND a.name = :name AND a.success = true")
    Optional<Activity> findSuccessfulActivity(@Param("workflowId") Long workflowId, @Param("name") String name);

    int countByWorkflowIdAndName(Long workflowId, String name);

    List<Activity> findByWorkflowIdOrderByStartedAt(Long workflowId);

    /** Latest activity (by startedAt) for each given workflow id — used to render "current activity". */
    @Query("""
            SELECT a FROM Activity a
            WHERE a.workflowId IN :ids
              AND a.startedAt = (SELECT MAX(b.startedAt) FROM Activity b WHERE b.workflowId = a.workflowId)
            """)
    List<Activity> findLatestActivities(@Param("ids") Collection<Long> ids);
}
