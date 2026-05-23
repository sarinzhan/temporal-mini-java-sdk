package com.beeline.workflow.web;

import com.beeline.workflow.engine.query.WorkflowQueryRuntime;
import com.beeline.workflow.engine.update.UpdateRegistry;
import com.beeline.workflow.engine.worker.WorkerLoop;
import com.beeline.workflow.engine.worker.WorkerLoopImpl;
import com.beeline.workflow.persistence.repository.ActivityOptionOverrideRepository;
import com.beeline.workflow.persistence.repository.EventRepository;
import com.beeline.workflow.persistence.repository.InstanceRegistryRepository;
import com.beeline.workflow.persistence.repository.RetryRepository;
import com.beeline.workflow.persistence.repository.SignalRepository;
import com.beeline.workflow.persistence.repository.TaskRepository;
import com.beeline.workflow.persistence.repository.UpdateRequestRepository;
import com.beeline.workflow.persistence.repository.WorkflowRepository;
import com.beeline.workflow.registry.WorkflowRegistry;
import com.beeline.workflow.spring.autoconfigure.WorkflowProperties;
import com.beeline.workflow.web.controller.ActivityOverridesController;
import com.beeline.workflow.web.controller.AdminExceptionHandler;
import com.beeline.workflow.web.controller.ClusterController;
import com.beeline.workflow.web.controller.WorkflowAdminController;
import com.beeline.workflow.web.controller.WorkflowInvocationController;
import com.beeline.workflow.web.controller.WorkflowsController;
import com.beeline.workflow.web.service.ActivityOptionsOverrideService;
import com.beeline.workflow.web.service.WorkflowAdminService;
import com.beeline.workflow.web.service.WorkflowInvocationService;
import com.beeline.workflow.web.service.WorkflowQueryService;
import tools.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@ConditionalOnWebApplication
@ConditionalOnClass(name = "org.springframework.web.bind.annotation.RestController")
public class WorkflowWebAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ClusterController clusterController(WorkflowProperties properties,
                                               InstanceRegistryRepository instanceRegistryRepository,
                                               TaskRepository taskRepository,
                                               WorkerLoop workerLoop) {
        if (!(workerLoop instanceof WorkerLoopImpl impl)) {
            throw new IllegalStateException(
                    "ClusterController requires WorkerLoopImpl (custom WorkerLoop replaced default — provide a custom controller too)");
        }
        return new ClusterController(properties, instanceRegistryRepository, taskRepository, impl);
    }

    @Bean
    @ConditionalOnMissingBean
    public WorkflowQueryService workflowQueryService(WorkflowRepository workflowRepository,
                                                     EventRepository eventRepository,
                                                     RetryRepository retryRepository) {
        return new WorkflowQueryService(workflowRepository, eventRepository, retryRepository);
    }

    @Bean
    @ConditionalOnMissingBean
    public WorkflowAdminService workflowAdminService(WorkflowRepository workflowRepository,
                                                     TaskRepository taskRepository,
                                                     EventRepository eventRepository,
                                                     RetryRepository retryRepository,
                                                     SignalRepository signalRepository) {
        return new WorkflowAdminService(workflowRepository, taskRepository, eventRepository,
                retryRepository, signalRepository);
    }

    @Bean
    @ConditionalOnMissingBean
    public ActivityOptionsOverrideService activityOptionsOverrideService(ActivityOptionOverrideRepository repository) {
        return new ActivityOptionsOverrideService(repository);
    }

    @Bean
    @ConditionalOnMissingBean
    public WorkflowsController workflowsController(WorkflowQueryService service) {
        return new WorkflowsController(service);
    }

    @Bean
    @ConditionalOnMissingBean
    public WorkflowAdminController workflowAdminController(WorkflowAdminService service) {
        return new WorkflowAdminController(service);
    }

    @Bean
    @ConditionalOnMissingBean
    public ActivityOverridesController activityOverridesController(ActivityOptionsOverrideService service) {
        return new ActivityOverridesController(service);
    }

    @Bean
    @ConditionalOnMissingBean
    public AdminExceptionHandler adminExceptionHandler() {
        return new AdminExceptionHandler();
    }

    @Bean
    @ConditionalOnMissingBean
    public WorkflowInvocationService workflowInvocationService(WorkflowQueryRuntime queryRuntime,
                                                               WorkflowRegistry workflowRegistry,
                                                               WorkflowRepository workflowRepository,
                                                               EventRepository eventRepository,
                                                               TaskRepository taskRepository,
                                                               UpdateRequestRepository updateRequestRepository,
                                                               UpdateRegistry updateRegistry,
                                                               ObjectMapper objectMapper) {
        return new WorkflowInvocationService(queryRuntime, workflowRegistry, workflowRepository,
                eventRepository, taskRepository, updateRequestRepository, updateRegistry, objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean
    public WorkflowInvocationController workflowInvocationController(WorkflowInvocationService service) {
        return new WorkflowInvocationController(service);
    }
}
