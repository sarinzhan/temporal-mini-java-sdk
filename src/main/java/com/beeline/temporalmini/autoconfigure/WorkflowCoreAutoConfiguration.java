package com.beeline.temporalmini.autoconfigure;

import com.beeline.temporalmini.metrics.ActivityMetrics;
import com.beeline.temporalmini.metrics.WorkflowMetrics;
import com.beeline.temporalmini.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
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
                                         ObjectMapper objectMapper,
                                         ObjectProvider<ActivityMetrics> activityMetrics,
                                         ObjectProvider<WorkflowMetrics> workflowMetrics) {
        return new WorkflowEngine(workflows, workflowRepository, activityRepository, objectMapper,
                activityMetrics.getIfAvailable(), workflowMetrics.getIfAvailable());
    }

    @Bean
    @ConditionalOnMissingBean(WorkflowRuntimeRegistry.class)
    public WorkflowRuntimeRegistry workflowRuntimeRegistry() {
        return new WorkflowRuntimeRegistry();
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
    public WorkflowScheduler workflowScheduler(WorkflowEngine engine,
                                               WorkflowRepository workflowRepository,
                                               @Qualifier(EXECUTOR_BEAN) ThreadPoolTaskExecutor executor,
                                               WorkflowRuntimeRegistry runtimeRegistry) {
        return new WorkflowScheduler(engine, workflowRepository, executor, runtimeRegistry);
    }
}
