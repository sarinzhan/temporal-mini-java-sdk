package com.beeline.temporalmini;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Periodic snapshot writer for the workflow metrics chart in the UI. Runs on a
 * fixed cadence (defaults to 10s) and inserts one {@link MetricSample} row.
 *
 * <p>Two scheduled methods:
 * <ul>
 *     <li>{@link #sample()} — append; bumped to second precision so the {@code ts}
 *     primary-key acts as a natural deduplication guard if two instances ever
 *     run in parallel.</li>
 *     <li>{@link #cleanup()} — drop rows older than the configured retention.</li>
 * </ul>
 */
@Slf4j
public class MetricsSampler {

    private final ThreadPoolTaskExecutor executor;
    private final WorkflowRuntimeRegistry runtimeRegistry;
    private final WorkflowRepository workflowRepository;
    private final MetricSampleRepository metricSampleRepository;
    private final MetricsProperties properties;

    @PersistenceContext
    private EntityManager entityManager;

    public MetricsSampler(ThreadPoolTaskExecutor executor,
                          WorkflowRuntimeRegistry runtimeRegistry,
                          WorkflowRepository workflowRepository,
                          MetricSampleRepository metricSampleRepository,
                          MetricsProperties properties) {
        this.executor = executor;
        this.runtimeRegistry = runtimeRegistry;
        this.workflowRepository = workflowRepository;
        this.metricSampleRepository = metricSampleRepository;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${workflow.metrics.sample-interval-ms:10000}")
    @Transactional
    public void sample() {
        ThreadPoolExecutor tp = executor.getThreadPoolExecutor();
        int active = tp.getActiveCount();
        int poolSize = tp.getPoolSize();

        MetricSample s = new MetricSample();
        s.setTs(LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS));
        s.setPoolActive(active);
        s.setPoolFree(Math.max(0, poolSize - active));
        s.setPoolQueue(tp.getQueue().size());
        s.setRuntimeCount(runtimeRegistry.ids().size());
        s.setCntNew(workflowRepository.countByState(WorkflowState.NEW));
        s.setCntRetry(workflowRepository.countByState(WorkflowState.RETRY));
        s.setCntBlocked(workflowRepository.countByState(WorkflowState.STOPPED));
        s.setCntFinished(workflowRepository.countByState(WorkflowState.FINISHED));
        s.setCntFailed(workflowRepository.countByState(WorkflowState.FAILED));

        try {
            metricSampleRepository.save(s);
        } catch (DataIntegrityViolationException dup) {
            // PK collision = a parallel writer beat us within the same second.
            // Harmless — drop this sample, the other one wins.
            log.debug("metric sample skipped (PK collision at {}): {}", s.getTs(), dup.getMessage());
        }
    }

    @Scheduled(cron = "${workflow.metrics.cleanup-cron:0 0 3 * * *}")
    @Transactional
    public void cleanup() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(properties.getRetentionDays());
        int deleted = metricSampleRepository.deleteOlderThan(cutoff);
        if (deleted > 0) {
            log.info("metric retention: deleted {} rows older than {}", deleted, cutoff);
        }
    }
}
