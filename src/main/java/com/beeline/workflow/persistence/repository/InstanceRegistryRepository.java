package com.beeline.workflow.persistence.repository;

import com.beeline.workflow.core.model.InstanceRegistryEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public interface InstanceRegistryRepository extends JpaRepository<InstanceRegistryEntity, String> {

    @Query(value = """
            SELECT * FROM wflow.instance_registry
            WHERE last_heartbeat > :staleSince
            ORDER BY id ASC
            """, nativeQuery = true)
    List<InstanceRegistryEntity> findLive(@Param("staleSince") Instant staleSince);
}
