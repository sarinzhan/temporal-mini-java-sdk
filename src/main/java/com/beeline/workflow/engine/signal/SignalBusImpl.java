package com.beeline.workflow.engine.signal;

import com.beeline.workflow.core.model.Signal;
import com.beeline.workflow.persistence.repository.SignalRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

public class SignalBusImpl implements SignalBus {

    private static final Logger log = LoggerFactory.getLogger(SignalBusImpl.class);

    private static final long POLL_INTERVAL_MS = 500L;

    private final SignalRepository signalRepository;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    public SignalBusImpl(SignalRepository signalRepository,
                         ObjectMapper objectMapper,
                         PlatformTransactionManager transactionManager) {
        this.signalRepository = signalRepository;
        this.objectMapper = objectMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    @Transactional
    public void send(Long workflowId, String signalName, Object payload) {
        Signal s = new Signal();
        s.setWorkflowId(workflowId);
        s.setSignalName(signalName);
        s.setPayload(serialize(payload));
        s.setConsumed(false);
        signalRepository.save(s);
        log.info("Signal sent to workflow {} name={}", workflowId, signalName);
    }

    @Override
    public Object await(Long workflowId, String signalName, Duration timeout) {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            Object payload = tryClaim(workflowId, signalName);
            if (payload != null) return payload;
            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("await interrupted", e);
            }
        }
        log.warn("Signal await timeout: workflow={} name={} timeout={}", workflowId, signalName, timeout);
        return null;
    }

    private Object tryClaim(Long workflowId, String signalName) {
        return transactionTemplate.execute(status -> {
            Optional<Signal> opt = signalRepository.claimFirstUnconsumed(workflowId, signalName);
            if (opt.isEmpty()) return null;
            Signal s = opt.get();
            s.setConsumed(true);
            signalRepository.save(s);
            return deserialize(s.getPayload());
        });
    }

    private String serialize(Object value) {
        if (value == null) return null;
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize signal payload", e);
        }
    }

    private Object deserialize(String json) {
        if (json == null) return null;
        try {
            return objectMapper.readValue(json, Object.class);
        } catch (Exception e) {
            throw new RuntimeException("Failed to deserialize signal payload", e);
        }
    }
}
