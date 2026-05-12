package com.beeline.temporalmini.autoconfigure;

import com.beeline.temporalmini.MetricSampleRepository;
import com.beeline.temporalmini.MetricsProperties;
import com.beeline.temporalmini.MetricsSampler;
import com.beeline.temporalmini.WorkflowRepository;
import com.beeline.temporalmini.WorkflowRuntimeRegistry;
import com.beeline.temporalmini.metrics.ActivityMetrics;
import com.beeline.temporalmini.metrics.LocalMeterRegistry;
import com.beeline.temporalmini.metrics.WorkflowMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import static com.beeline.temporalmini.autoconfigure.WorkflowCoreAutoConfiguration.EXECUTOR_BEAN;

@AutoConfiguration(after = WorkflowCoreAutoConfiguration.class)
@EnableConfigurationProperties(MetricsProperties.class)
public class WorkflowMetricsAutoConfiguration {

    /**
     * Fallback: no external MeterRegistry in context (actuator + backend not connected).
     * Creates a {@link LocalMeterRegistry} — SimpleMeterRegistry that the UI can read.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnMissingBean(MeterRegistry.class)
    static class LocalRegistryConfig {

        @Bean
        public LocalMeterRegistry localMeterRegistry() {
            return new LocalMeterRegistry();
        }
    }

    /**
     * Always resolves: uses the external {@link MeterRegistry} if actuator wired one up,
     * otherwise falls back to the {@link LocalMeterRegistry} created above.
     */
    @Bean
    public ActivityMetrics activityMetrics(ObjectProvider<MeterRegistry> registryProvider) {
        return new ActivityMetrics(registryProvider.getObject());
    }

    @Bean
    public WorkflowMetrics workflowMetrics(ObjectProvider<MeterRegistry> registryProvider,
                                           WorkflowRepository workflowRepository,
                                           @Qualifier(EXECUTOR_BEAN) ThreadPoolTaskExecutor executor) {
        return new WorkflowMetrics(registryProvider.getObject(), workflowRepository, executor);
    }

    @Bean
    @ConditionalOnProperty(name = "workflow.metrics.enabled", havingValue = "true", matchIfMissing = true)
    public MetricsSampler metricsSampler(@Qualifier(EXECUTOR_BEAN) ThreadPoolTaskExecutor executor,
                                         WorkflowRuntimeRegistry runtimeRegistry,
                                         WorkflowRepository workflowRepository,
                                         MetricSampleRepository metricSampleRepository,
                                         MetricsProperties properties) {
        return new MetricsSampler(executor, runtimeRegistry, workflowRepository, metricSampleRepository, properties);
    }
}
