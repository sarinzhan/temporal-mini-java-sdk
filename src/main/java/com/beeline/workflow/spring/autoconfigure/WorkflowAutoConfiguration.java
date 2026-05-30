package com.beeline.workflow.spring.autoconfigure;

import com.beeline.workflow.engine.cluster.InstanceRegistryService;
import com.beeline.workflow.engine.codec.JacksonJsonFormatMapper;
import com.beeline.workflow.engine.codec.PayloadCodec;
import com.beeline.workflow.engine.command.CommandDispatcher;
import com.beeline.workflow.engine.command.CommandHandler;
import com.beeline.workflow.engine.command.WorkflowCommand;
import com.beeline.workflow.engine.command.handler.ActivityCommandHandler;
import com.beeline.workflow.engine.command.handler.SideEffectCommandHandler;
import com.beeline.workflow.engine.command.handler.VersionCommandHandler;
import com.beeline.workflow.engine.lifecycle.WorkflowOutcomeMapper;
import com.beeline.workflow.engine.replay.EventLogFactory;
import com.beeline.workflow.engine.retry.RetryDecider;
import com.beeline.workflow.engine.scheduler.TimeoutWatcher;
import com.beeline.workflow.engine.scheduler.TimeoutWatcherImpl;
import com.beeline.workflow.engine.scheduler.WakeupScheduler;
import com.beeline.workflow.engine.scheduler.WakeupSchedulerImpl;
import com.beeline.workflow.engine.turn.TurnCommitter;
import com.beeline.workflow.engine.turn.WorkflowTurnRunner;
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
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.util.List;

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
    public WorkflowRegistry workflowRegistry(ApplicationContext applicationContext) {
        return new WorkflowRegistry(applicationContext);
    }

    @Bean
    @ConditionalOnMissingBean
    public PayloadCodec payloadCodec(ObjectMapper objectMapper) {
        return new PayloadCodec(objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean
    public RetryDecider retryDecider() {
        return new RetryDecider();
    }

    /**
     * Wire Hibernate's JSON {@code FormatMapper} to the application's Jackson 3 ObjectMapper. Without
     * this, JSONB columns ({@code @JdbcTypeCode(SqlTypes.JSON)}) fail at runtime because Hibernate's
     * auto-detection only recognises Jackson 2 / Yasson, neither of which is on the classpath.
     */
    @Bean
    @ConditionalOnMissingBean(name = "workflowJsonFormatMapperCustomizer")
    public HibernatePropertiesCustomizer workflowJsonFormatMapperCustomizer(ObjectMapper objectMapper) {
        return props -> props.put("hibernate.type.json_format_mapper",
                new JacksonJsonFormatMapper(objectMapper));
    }

    @Bean
    @ConditionalOnMissingBean
    public EventLogFactory eventLogFactory(PayloadCodec codec) {
        return new EventLogFactory(codec);
    }

    @Bean
    @ConditionalOnMissingBean
    public TurnCommitter turnCommitter(EventRepository eventRepository,
                                       ScheduleRepository scheduleRepository,
                                       TaskRepository taskRepository,
                                       WorkflowRepository workflowRepository,
                                       PlatformTransactionManager transactionManager) {
        return new TurnCommitter(eventRepository, scheduleRepository, taskRepository,
                workflowRepository, transactionManager);
    }

    @Bean
    @ConditionalOnMissingBean
    public WorkflowOutcomeMapper workflowOutcomeMapper(PayloadCodec codec) {
        return new WorkflowOutcomeMapper(codec);
    }

    @Bean
    @ConditionalOnMissingBean
    public ActivityCommandHandler activityCommandHandler(RetryDecider retryDecider,
                                                         WorkflowProperties properties) {
        return new ActivityCommandHandler(retryDecider, (name, opts) -> opts,
                properties.getActivityMaxThreads());
    }

    @Bean
    @ConditionalOnMissingBean
    public SideEffectCommandHandler sideEffectCommandHandler() {
        return new SideEffectCommandHandler();
    }

    @Bean
    @ConditionalOnMissingBean
    public VersionCommandHandler versionCommandHandler() {
        return new VersionCommandHandler();
    }

    @Bean
    @ConditionalOnMissingBean
    public CommandDispatcher commandDispatcher(List<CommandHandler<? extends WorkflowCommand>> handlers) {
        return new CommandDispatcher(handlers);
    }

    @Bean
    @ConditionalOnMissingBean
    public WorkflowTurnRunner workflowTurnRunner(WorkflowRegistry workflowRegistry,
                                                 WorkflowRepository workflowRepository,
                                                 EventRepository eventRepository,
                                                 ObjectMapper objectMapper,
                                                 PayloadCodec codec,
                                                 EventLogFactory eventLogFactory,
                                                 WorkflowOutcomeMapper outcomeMapper,
                                                 CommandDispatcher dispatcher,
                                                 TurnCommitter turnCommitter) {
        return new WorkflowTurnRunner(workflowRegistry, workflowRepository, eventRepository,
                objectMapper, codec, eventLogFactory, outcomeMapper, dispatcher, turnCommitter);
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
                                 WorkflowTurnRunner turnRunner,
                                 WorkflowProperties properties,
                                 PlatformTransactionManager transactionManager) {
        return new WorkerLoopImpl(taskRepository, turnRunner, properties, transactionManager);
    }

    @Bean
    @ConditionalOnMissingBean
    public WakeupScheduler wakeupScheduler(ScheduleRepository scheduleRepository,
                                           EventRepository eventRepository,
                                           TaskRepository taskRepository,
                                           WorkflowRepository workflowRepository) {
        return new WakeupSchedulerImpl(scheduleRepository, eventRepository, taskRepository,
                workflowRepository);
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
                                         ObjectMapper objectMapper,
                                         PlatformTransactionManager transactionManager) {
        return new WorkflowClientImpl(workflowRepository, taskRepository, eventRepository,
                workflowRegistry, objectMapper, transactionManager);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(name = "workflow.instance.external-url")
    public InstanceRegistryService instanceRegistryService(InstanceRegistryRepository repository,
                                                           WorkflowProperties properties) {
        return new InstanceRegistryService(repository, properties);
    }
}
