package com.beeline.workflow.spring.autoconfigure;

import com.beeline.workflow.core.api.Workflow;
import com.beeline.workflow.engine.cluster.InstanceRegistryService;
import com.beeline.workflow.engine.executor.ActivityExecutor;
import com.beeline.workflow.engine.executor.ActivityExecutorImpl;
import com.beeline.workflow.engine.executor.WorkflowExecutor;
import com.beeline.workflow.engine.scheduler.TimeoutWatcher;
import com.beeline.workflow.engine.scheduler.TimeoutWatcherImpl;
import com.beeline.workflow.engine.scheduler.WakeupScheduler;
import com.beeline.workflow.engine.scheduler.WakeupSchedulerImpl;
import com.beeline.workflow.engine.worker.WorkerLoop;
import com.beeline.workflow.engine.worker.WorkerLoopImpl;
import com.beeline.workflow.persistence.repository.EventRepository;
import com.beeline.workflow.persistence.repository.InstanceRegistryRepository;
import com.beeline.workflow.persistence.repository.ScheduleRepository;
import com.beeline.workflow.persistence.repository.TaskRepository;
import com.beeline.workflow.persistence.repository.WorkflowRepository;
import com.beeline.workflow.registry.RegistryInitializer;
import com.beeline.workflow.registry.WorkflowRegistry;
import com.beeline.workflow.spring.api.WorkflowClient;
import com.beeline.workflow.spring.api.WorkflowClientImpl;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;

import javax.sql.DataSource;

@AutoConfiguration
@EnableScheduling
@EnableConfigurationProperties(WorkflowProperties.class)
@EnableJpaRepositories(basePackages = "com.beeline.workflow.persistence.repository")
@EntityScan(basePackages = "com.beeline.workflow.core.model")
public class WorkflowAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public WorkflowSchemaInitializer workflowSchemaInitializer(DataSource dataSource) {
        return new WorkflowSchemaInitializer(dataSource);
    }

    @Bean
    @ConditionalOnMissingBean
    public WorkflowStartupBanner workflowStartupBanner(WorkflowProperties properties) {
        return new WorkflowStartupBanner(properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public WorkflowRegistry workflowRegistry() {
        return new WorkflowRegistry();
    }

    @Bean
    @ConditionalOnMissingBean
    public ActivityExecutor activityExecutor(EventRepository eventRepository,
                                             ScheduleRepository scheduleRepository,
                                             ObjectMapper objectMapper,
                                             PlatformTransactionManager transactionManager) {
        // Install the mapper used by Workflow.sideEffect / getVersion.
        Workflow.installObjectMapper(objectMapper);
        return new ActivityExecutorImpl(eventRepository, scheduleRepository, objectMapper, transactionManager);
    }

    @Bean
    @ConditionalOnMissingBean
    public WorkflowExecutor workflowExecutor(WorkflowRegistry workflowRegistry,
                                             ActivityExecutor activityExecutor,
                                             WorkflowRepository workflowRepository,
                                             EventRepository eventRepository,
                                             ObjectMapper objectMapper,
                                             PlatformTransactionManager transactionManager) {
        return new WorkflowExecutor(workflowRegistry, activityExecutor, workflowRepository,
                eventRepository, objectMapper, transactionManager);
    }

    @Bean
    @ConditionalOnMissingBean
    public RegistryInitializer registryInitializer(ApplicationContext applicationContext,
                                                   WorkflowRegistry workflowRegistry) {
        return new RegistryInitializer(applicationContext, workflowRegistry);
    }

    @Bean
    @ConditionalOnMissingBean
    public WorkerLoop workerLoop(TaskRepository taskRepository,
                                 WorkflowExecutor workflowExecutor,
                                 WorkflowProperties properties,
                                 PlatformTransactionManager transactionManager) {
        return new WorkerLoopImpl(taskRepository, workflowExecutor, properties, transactionManager);
    }

    @Bean
    @ConditionalOnMissingBean
    public WakeupScheduler wakeupScheduler(ScheduleRepository scheduleRepository,
                                           EventRepository eventRepository,
                                           TaskRepository taskRepository) {
        return new WakeupSchedulerImpl(scheduleRepository, eventRepository, taskRepository);
    }

    @Bean
    @ConditionalOnMissingBean
    public TimeoutWatcher timeoutWatcher(TaskRepository taskRepository) {
        return new TimeoutWatcherImpl(taskRepository);
    }

    @Bean
    @ConditionalOnMissingBean
    public WorkflowClient workflowClient(WorkflowRepository workflowRepository,
                                         TaskRepository taskRepository,
                                         EventRepository eventRepository,
                                         WorkflowRegistry workflowRegistry,
                                         ObjectMapper objectMapper) {
        return new WorkflowClientImpl(workflowRepository, taskRepository, eventRepository,
                workflowRegistry, objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(name = "workflow.instance.external-url")
    public InstanceRegistryService instanceRegistryService(InstanceRegistryRepository repository,
                                                           WorkflowProperties properties) {
        return new InstanceRegistryService(repository, properties);
    }
}
