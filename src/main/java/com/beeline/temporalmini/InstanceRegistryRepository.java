package com.beeline.temporalmini;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface InstanceRegistryRepository extends JpaRepository<InstanceRegistryEntity, String> {

    List<InstanceRegistryEntity> findByLastHeartbeatAfter(LocalDateTime threshold);
}
