package com.beeline.workflow.spring.autoconfigure;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;

public class WorkflowSchemaInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(WorkflowSchemaInitializer.class);

    private final DataSource dataSource;

    public WorkflowSchemaInitializer(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (schemaExists()) {
            log.info("Workflow schema already exists - skipping initialization");
            return;
        }
        log.info("Workflow schema not found - creating tables from schema.sql");
        try (Connection conn = dataSource.getConnection()) {
            ResourceDatabasePopulator populator = new ResourceDatabasePopulator();
            populator.addScript(new ClassPathResource("schema.sql"));
            populator.setContinueOnError(false);
            populator.populate(conn);
        }
        log.info("Workflow schema created successfully");
    }

    private boolean schemaExists() throws Exception {
        try (Connection conn = dataSource.getConnection();
             ResultSet rs = conn.getMetaData().getTables(null, "wflow", "workflows", new String[]{"TABLE"})) {
            return rs.next();
        }
    }
}
