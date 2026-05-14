package com.beeline.temporalmini;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.LocalDateTime;
import java.util.UUID;

@Slf4j
public class InstanceRegistryService {

    private final InstanceRegistryRepository repository;
    private final String instanceUrl;
    private final String instanceId;

    public InstanceRegistryService(InstanceRegistryRepository repository, String instanceUrl) {
        this.repository = repository;
        this.instanceUrl = instanceUrl;
        this.instanceId = UUID.randomUUID().toString();
    }

    public String getInstanceId() {
        return instanceId;
    }

    public String getInstanceUrl() {
        return instanceUrl;
    }

    @PostConstruct
    public void register() {
        if (instanceUrl == null || instanceUrl.isBlank()) {
            log.warn("INSTANCE_URL is blank — instance will not be registered");
            return;
        }
        InstanceRegistryEntity entity = new InstanceRegistryEntity();
        entity.setId(instanceId);
        entity.setUrl(instanceUrl);
        entity.setLastHeartbeat(LocalDateTime.now());
        repository.save(entity);
        log.info("Instance registered: id={}, url={}", instanceId, instanceUrl);
    }

    @PreDestroy
    public void deregister() {
        repository.deleteById(instanceId);
        log.info("Instance deregistered: id={}", instanceId);
    }

    @Scheduled(fixedDelay = 10_000)
    public void heartbeat() {
        repository.findById(instanceId).ifPresent(entity -> {
            entity.setLastHeartbeat(LocalDateTime.now());
            repository.save(entity);
        });
    }
}
