package com.beeline.workflow.engine.cluster;

import com.beeline.workflow.core.model.InstanceRegistryEntity;
import com.beeline.workflow.persistence.repository.InstanceRegistryRepository;
import com.beeline.workflow.spring.autoconfigure.WorkflowProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

public class InstanceRegistryService {

    private static final Logger log = LoggerFactory.getLogger(InstanceRegistryService.class);

    public static final Duration STALE_THRESHOLD = Duration.ofSeconds(30);

    private final InstanceRegistryRepository repository;
    private final WorkflowProperties properties;

    public InstanceRegistryService(InstanceRegistryRepository repository, WorkflowProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    @PostConstruct
    @Transactional
    public void register() {
        var instance = properties.getInstance();
        InstanceRegistryEntity entity = repository.findById(instance.getId())
                .orElseGet(InstanceRegistryEntity::new);
        entity.setId(instance.getId());
        entity.setInternalUrl(instance.getInternalUrl());
        entity.setExternalUrl(instance.getExternalUrl());
        entity.setLastHeartbeat(Instant.now());
        repository.save(entity);
        log.info("Cluster: registered instance id={} internal={} external={}",
                instance.getId(), instance.getInternalUrl(), instance.getExternalUrl());
    }

    @Scheduled(fixedDelay = 10_000)
    @Transactional
    public void heartbeat() {
        var instance = properties.getInstance();
        InstanceRegistryEntity entity = repository.findById(instance.getId()).orElse(null);
        if (entity == null) {
            register();
            return;
        }
        entity.setInternalUrl(instance.getInternalUrl());
        entity.setExternalUrl(instance.getExternalUrl());
        entity.setLastHeartbeat(Instant.now());
        repository.save(entity);
    }

    @PreDestroy
    @Transactional
    public void deregister() {
        var instance = properties.getInstance();
        try {
            repository.deleteById(instance.getId());
            log.info("Cluster: deregistered instance id={}", instance.getId());
        } catch (Exception ex) {
            log.warn("Cluster: deregister failed for {}: {}", instance.getId(), ex.getMessage());
        }
    }
}
