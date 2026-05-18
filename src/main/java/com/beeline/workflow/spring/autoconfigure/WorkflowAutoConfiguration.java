package com.beeline.workflow.spring.autoconfigure;

import com.beeline.workflow.core.api.Workflow;
import com.beeline.workflow.engine.executor.ActivityExecutor;
import com.beeline.workflow.engine.executor.ActivityExecutorImpl;
import com.beeline.workflow.engine.executor.WorkflowExecutor;
import com.beeline.workflow.engine.scheduler.RetryScheduler;
import com.beeline.workflow.engine.scheduler.RetrySchedulerImpl;
import com.beeline.workflow.engine.scheduler.TimeoutWatcher;
import com.beeline.workflow.engine.scheduler.TimeoutWatcherImpl;
import com.beeline.workflow.engine.cluster.InstanceRegistryService;
import com.beeline.workflow.engine.signal.SignalBus;
import com.beeline.workflow.engine.signal.SignalBusImpl;
import com.beeline.workflow.engine.stub.ActivityStubFactory;
import com.beeline.workflow.engine.worker.WorkerLoop;
import com.beeline.workflow.engine.worker.WorkerLoopImpl;
import com.beeline.workflow.persistence.repository.ActivityResultRepository;
import com.beeline.workflow.persistence.repository.EventRepository;
import com.beeline.workflow.persistence.repository.InstanceRegistryRepository;
import com.beeline.workflow.persistence.repository.RetryRepository;
import com.beeline.workflow.persistence.repository.SignalRepository;
import com.beeline.workflow.persistence.repository.TaskRepository;
import com.beeline.workflow.persistence.repository.WorkflowRepository;
import com.beeline.workflow.registry.ActivityRegistry;
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

@AutoConfiguration
@EnableScheduling
@EnableConfigurationProperties(WorkflowProperties.class)
@EnableJpaRepositories(basePackages = "com.beeline.workflow.persistence.repository")
@EntityScan(basePackages = "com.beeline.workflow.core.model")
public class WorkflowAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ActivityRegistry activityRegistry() {
        return new ActivityRegistry();
    }

    @Bean
    @ConditionalOnMissingBean
    public WorkflowRegistry workflowRegistry() {
        return new WorkflowRegistry();
    }

    @Bean
    @ConditionalOnMissingBean
    public ActivityExecutor activityExecutor(ActivityResultRepository activityResultRepository,
                                             EventRepository eventRepository,
                                             RetryRepository retryRepository,
                                             ObjectMapper objectMapper,
                                             org.springframework.beans.factory.ObjectProvider<com.beeline.workflow.web.service.ActivityOptionsOverrideService> overrideProvider) {
        com.beeline.workflow.web.service.ActivityOptionsOverrideService overrides = overrideProvider.getIfAvailable();
        if (overrides == null) {
            return new ActivityExecutorImpl(activityResultRepository, eventRepository, retryRepository, objectMapper);
        }
        return new ActivityExecutorImpl(activityResultRepository, eventRepository, retryRepository, objectMapper,
                overrides::resolve);
    }

    @Bean
    @ConditionalOnMissingBean
    public ActivityStubFactory activityStubFactory(ActivityExecutor activityExecutor,
                                                   ActivityRegistry activityRegistry) {
        return new ActivityStubFactory(activityExecutor, activityRegistry);
    }

    @Bean
    @ConditionalOnMissingBean
    public WorkflowExecutor workflowExecutor(WorkflowRegistry workflowRegistry,
                                             ActivityRegistry activityRegistry,
                                             ActivityExecutor activityExecutor,
                                             WorkflowRepository workflowRepository,
                                             EventRepository eventRepository,
                                             ObjectMapper objectMapper,
                                             PlatformTransactionManager transactionManager) {
        return new WorkflowExecutor(workflowRegistry, activityRegistry, activityExecutor,
                workflowRepository, eventRepository, objectMapper, transactionManager);
    }

    @Bean
    @ConditionalOnMissingBean
    public RegistryInitializer registryInitializer(ApplicationContext applicationContext,
                                                   ActivityRegistry activityRegistry,
                                                   WorkflowRegistry workflowRegistry) {
        return new RegistryInitializer(applicationContext, activityRegistry, workflowRegistry);
    }

    @Bean
    @ConditionalOnMissingBean
    public SignalBus signalBus(SignalRepository signalRepository,
                               ObjectMapper objectMapper,
                               PlatformTransactionManager transactionManager) {
        SignalBusImpl bus = new SignalBusImpl(signalRepository, objectMapper, transactionManager);
        Workflow.installSignalBus(bus);
        return bus;
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
    public RetryScheduler retryScheduler(RetryRepository retryRepository, TaskRepository taskRepository) {
        return new RetrySchedulerImpl(retryRepository, taskRepository);
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
                                         WorkflowRegistry workflowRegistry,
                                         ObjectMapper objectMapper) {
        return new WorkflowClientImpl(workflowRepository, taskRepository, workflowRegistry, objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(name = "workflow.instance.external-url")
    public InstanceRegistryService instanceRegistryService(InstanceRegistryRepository repository,
                                                           WorkflowProperties properties) {
        return new InstanceRegistryService(repository, properties);
    }
}
