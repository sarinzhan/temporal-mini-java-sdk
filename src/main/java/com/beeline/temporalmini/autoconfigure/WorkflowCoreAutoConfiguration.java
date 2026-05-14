package com.beeline.temporalmini.autoconfigure;

import com.beeline.temporalmini.metrics.ActivityMetrics;
import com.beeline.temporalmini.metrics.WorkflowMetrics;
import com.beeline.temporalmini.*;
import com.beeline.temporalmini.ui.NodeStateController;
import com.beeline.temporalmini.ui.UiAggregatorController;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.concurrent.ThreadPoolExecutor;

@AutoConfiguration(after = WorkflowPersistenceAutoConfiguration.class)
@EnableScheduling
public class WorkflowCoreAutoConfiguration {

    public static final String EXECUTOR_BEAN = "workflowExecutor";

    @Bean
    public WorkflowEngine workflowEngine(List<Workflow> workflows,
                                         WorkflowRepository workflowRepository,
                                         ActivityRepository activityRepository,
                                         WorkflowHistoryRepository workflowHistoryRepository,
                                         ActivityHistoryRepository activityHistoryRepository,
                                         ObjectMapper objectMapper,
                                         ObjectProvider<ActivityMetrics> activityMetrics,
                                         ObjectProvider<WorkflowMetrics> workflowMetrics) {
        return new WorkflowEngine(workflows, workflowRepository, activityRepository,
                workflowHistoryRepository, activityHistoryRepository, objectMapper,
                activityMetrics.getIfAvailable(), workflowMetrics.getIfAvailable());
    }

    @Bean
    @ConditionalOnMissingBean(WorkflowRuntimeRegistry.class)
    public WorkflowRuntimeRegistry workflowRuntimeRegistry() {
        return new WorkflowRuntimeRegistry();
    }

    @Bean
    @ConditionalOnMissingBean(WorkflowExecutor.class)
    public WorkflowExecutor workflowTaskExecutor(WorkflowRepository workflowRepository,
                                                  WorkflowEngine engine) {
        return new WorkflowExecutor(workflowRepository, engine);
    }

    /**
     * Bounded executor for running workflows in parallel.
     * Defaults: poolSize = CPU count, queueCapacity = 100, AbortPolicy.
     *
     * <p>Returned as {@link ThreadPoolTaskExecutor} (not just {@code Executor}) so
     * {@code WorkflowUiController} can read live pool metrics for {@code GET /pool}.
     */
    @Bean(name = EXECUTOR_BEAN, destroyMethod = "shutdown")
    @ConditionalOnMissingBean(name = EXECUTOR_BEAN)
    public ThreadPoolTaskExecutor workflowExecutor(
            @Value("${workflow.scheduler.pool-size:#{T(java.lang.Runtime).getRuntime().availableProcessors()}}") int poolSize,
            @Value("${workflow.scheduler.queue-capacity:100}") int queueCapacity,
            @Value("${workflow.scheduler.thread-name-prefix:wflow-}") String threadNamePrefix) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(poolSize);
        executor.setMaxPoolSize(poolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix(threadNamePrefix);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }

    @Bean
    @ConditionalOnProperty(name = "workflow.scheduler.enabled", havingValue = "true", matchIfMissing = true)
    public WorkflowScheduler workflowScheduler(WorkflowRepository workflowRepository,
                                               @Qualifier(EXECUTOR_BEAN) ThreadPoolTaskExecutor executor,
                                               WorkflowRuntimeRegistry runtimeRegistry,
                                               WorkflowExecutor workflowTaskExecutor) {
        return new WorkflowScheduler(workflowRepository, executor, runtimeRegistry, workflowTaskExecutor);
    }

    @Bean
    @ConditionalOnProperty(name = "workflow.instance.url")
    public InstanceRegistryService instanceRegistryService(
            InstanceRegistryRepository instanceRegistryRepository,
            @Value("${workflow.instance.url}") String instanceUrl) {
        return new InstanceRegistryService(instanceRegistryRepository, instanceUrl);
    }

    @Bean
    @ConditionalOnMissingBean(RestClient.class)
    public RestClient workflowRestClient() {
        return RestClient.create();
    }

    @Bean
    @ConditionalOnBean(InstanceRegistryService.class)
    public NodeStateController nodeStateController(InstanceRegistryService instanceRegistryService,
                                                    WorkflowRuntimeRegistry runtimeRegistry,
                                                    @Qualifier(EXECUTOR_BEAN) ThreadPoolTaskExecutor executor) {
        return new NodeStateController(instanceRegistryService, runtimeRegistry, executor);
    }

    @Bean
    @ConditionalOnBean(InstanceRegistryService.class)
    public UiAggregatorController uiAggregatorController(InstanceRegistryRepository instanceRegistryRepository,
                                                          RestClient workflowRestClient) {
        return new UiAggregatorController(instanceRegistryRepository, workflowRestClient);
    }
}
