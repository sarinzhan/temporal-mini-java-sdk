package com.beeline.workflow.web;

import com.beeline.workflow.persistence.repository.EventRepository;
import com.beeline.workflow.persistence.repository.InstanceRegistryRepository;
import com.beeline.workflow.persistence.repository.MetricsSnapshotRepository;
import com.beeline.workflow.persistence.repository.TaskRepository;
import com.beeline.workflow.persistence.repository.WorkflowRepository;
import com.beeline.workflow.spring.api.WorkflowClient;
import com.beeline.workflow.spring.autoconfigure.WorkflowProperties;
import com.beeline.workflow.web.controller.MetricsController;
import com.beeline.workflow.web.controller.WorkflowController;
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
    public WorkflowController workflowController(WorkflowClient client,
                                                 WorkflowRepository workflowRepository,
                                                 EventRepository eventRepository) {
        return new WorkflowController(client, workflowRepository, eventRepository);
    }

    @Bean
    @ConditionalOnMissingBean
    public MetricsController metricsController(MetricsSnapshotRepository snapshotRepository,
                                               InstanceRegistryRepository instanceRepository,
                                               TaskRepository taskRepository,
                                               WorkflowProperties properties) {
        return new MetricsController(snapshotRepository, instanceRepository, taskRepository,
                properties);
    }
}
