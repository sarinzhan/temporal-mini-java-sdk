package com.beeline.workflow.spring.autoconfigure;

import com.beeline.workflow.engine.metrics.MetricsCollector;
import com.beeline.workflow.engine.metrics.WorkflowMetrics;
import com.beeline.workflow.persistence.repository.EventRepository;
import com.beeline.workflow.persistence.repository.MetricsSnapshotRepository;
import com.beeline.workflow.persistence.repository.ScheduleRepository;
import com.beeline.workflow.persistence.repository.TaskRepository;
import com.beeline.workflow.persistence.repository.WorkflowRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the metrics rollup. The {@link MetricsCollector} always runs (it only needs the database)
 * and persists a {@code wflow.metrics_snapshot} row each interval for the UI. The Micrometer
 * facade is created only when micrometer is on the classpath; if a host application also exposes a
 * registry (e.g. via actuator + a Prometheus registry), the same numbers are published as meters.
 * Disable the whole feature with {@code workflow.metrics.enabled=false}.
 */
@AutoConfiguration(after = WorkflowAutoConfiguration.class)
@EnableConfigurationProperties(WorkflowProperties.class)
@ConditionalOnProperty(prefix = "workflow.metrics", name = "enabled", matchIfMissing = true)
public class WorkflowMetricsAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public MetricsCollector metricsCollector(WorkflowRepository workflowRepository,
                                             TaskRepository taskRepository,
                                             ScheduleRepository scheduleRepository,
                                             EventRepository eventRepository,
                                             MetricsSnapshotRepository snapshotRepository,
                                             ObjectProvider<WorkflowMetrics> metrics,
                                             WorkflowProperties properties) {
        WorkflowMetrics facade = metrics.getIfAvailable(() -> new WorkflowMetrics(null));
        return new MetricsCollector(workflowRepository, taskRepository, scheduleRepository,
                eventRepository, snapshotRepository, facade, properties);
    }

    /**
     * Only loaded when micrometer is present. {@code ObjectProvider<MeterRegistry>} resolves the
     * registry lazily so this stays independent of how/when the host configures its registry; with
     * no registry the facade is a no-op and only the DB snapshot is written.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(MeterRegistry.class)
    static class MicrometerConfiguration {

        @Bean
        @ConditionalOnMissingBean
        public WorkflowMetrics workflowMetrics(ObjectProvider<MeterRegistry> registry) {
            return new WorkflowMetrics(registry.getIfAvailable());
        }
    }
}
