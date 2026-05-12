package com.beeline.temporalmini.ui;

import com.beeline.temporalmini.WorkflowSecurityProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.Environment;

/**
 * Logs a clickable URL for the admin UI right after Spring Boot signals readiness.
 * Saves operators from guessing where the dashboard ended up — especially handy
 * when {@code server.port=0} (random port) or a context path is set.
 */
@Slf4j
public class WorkflowUiBanner implements ApplicationListener<ApplicationReadyEvent> {

    private final ObjectProvider<WorkflowSecurityProperties> securityProperties;

    public WorkflowUiBanner(ObjectProvider<WorkflowSecurityProperties> securityProperties) {
        this.securityProperties = securityProperties;
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        Environment env = event.getApplicationContext().getEnvironment();
        String port = firstNonBlank(
                env.getProperty("local.server.port"),
                env.getProperty("server.port"),
                "8080");
        String contextPath = normalize(env.getProperty("server.servlet.context-path"));
        String base = "http://localhost:" + port + contextPath + "/temporal-mini";

        log.info("=========================================================");
        log.info("  Workflow admin UI: {}/ui/", base);
        log.info("  REST API base:     {}/api", base);
        WorkflowSecurityProperties props = securityProperties.getIfAvailable();
        if (props != null && props.getSecurity().isEnabled()) {
            log.info("  Auth:              ENABLED  (login user: {})", props.getUsername());
        } else {
            log.info("  Auth:              disabled (workflow.ui.security.enabled=false)");
        }
        log.info("=========================================================");
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) return v;
        }
        return "";
    }

    private static String normalize(String contextPath) {
        if (contextPath == null || contextPath.isBlank() || "/".equals(contextPath)) return "";
        return contextPath.startsWith("/") ? contextPath : "/" + contextPath;
    }
}
