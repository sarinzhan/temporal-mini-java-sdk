package com.beeline.temporalmini.autoconfigure;

import com.beeline.temporalmini.TemporalMiniSchemaMigrator;
import com.beeline.temporalmini.WorkflowEntity;
import com.beeline.temporalmini.WorkflowRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.jpa.autoconfigure.EntityManagerFactoryDependsOnPostProcessor;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

import javax.sql.DataSource;

@AutoConfiguration(after = DataSourceAutoConfiguration.class, before = HibernateJpaAutoConfiguration.class)
@EnableJpaRepositories(basePackageClasses = WorkflowRepository.class)
@EntityScan(basePackageClasses = WorkflowEntity.class)
public class WorkflowPersistenceAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(WorkflowPersistenceAutoConfiguration.class);

    /**
     * Applies DB migrations from {@code classpath:db/migration/temporal-mini/V*__*.sql}.
     * Versions are tracked in {@code wflow.sql_migrations}.
     * {@link SchemaMigratorJpaDependencyConfig} ensures the JPA EMF waits on this bean.
     */
    @Bean
    @ConditionalOnBean(DataSource.class)
    @ConditionalOnMissingBean(TemporalMiniSchemaMigrator.class)
    public TemporalMiniSchemaMigrator temporalMiniSchemaMigrator(DataSource dataSource) {
        log.debug("Running temporal-mini schema migrations");
        TemporalMiniSchemaMigrator migrator = new TemporalMiniSchemaMigrator(dataSource);
        migrator.migrate();
        return migrator;
    }

    /** Forces {@code EntityManagerFactory} to wait until migrations have run. */
    static class SchemaMigratorJpaDependencyConfig extends EntityManagerFactoryDependsOnPostProcessor {
        SchemaMigratorJpaDependencyConfig() {
            super("temporalMiniSchemaMigrator");
        }
    }

    @Bean
    public static SchemaMigratorJpaDependencyConfig schemaMigratorJpaDependencyConfig() {
        return new SchemaMigratorJpaDependencyConfig();
    }
}
