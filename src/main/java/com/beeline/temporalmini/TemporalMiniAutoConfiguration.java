package com.beeline.temporalmini;

import org.flywaydb.core.Flyway;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.*;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;
import tools.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.util.List;

@AutoConfiguration(after = DataSourceAutoConfiguration.class, before = HibernateJpaAutoConfiguration.class)
@EnableJpaRepositories(basePackageClasses = WorkflowRepository.class)
@EntityScan(basePackageClasses = WorkflowEntity.class)
@EnableScheduling
public class TemporalMiniAutoConfiguration {

    @Bean
    public WorkflowEngine workflowEngine(List<Workflow> workflows,
                                         WorkflowRepository workflowRepository,
                                         ActivityRepository activityRepository,
                                         ObjectMapper objectMapper) {
        return new WorkflowEngine(workflows, workflowRepository, activityRepository, objectMapper);
    }

    @Bean
    @ConditionalOnProperty(name = "workflow.scheduler.enabled", havingValue = "true", matchIfMissing = true)
    public WorkflowScheduler workflowScheduler(WorkflowEngine engine,
                                               WorkflowRepository workflowRepository) {
        return new WorkflowScheduler(engine, workflowRepository);
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(Flyway.class)
    static class TemporalMiniFlywayConfiguration {

        @Bean
        @ConditionalOnBean(DataSource.class)
        public Flyway temporalMiniFlyway(DataSource dataSource) {
            Flyway flyway = Flyway.configure()
                    .dataSource(dataSource)
                    .locations("classpath:db/migration/temporal-mini")
                    .defaultSchema("wflow")
                    .createSchemas(true)
                    .table("flyway_temporal_mini_history")
                    .load();
            flyway.migrate();
            return flyway;
        }
    }
}
