package com.beeline.workflow.spring.autoconfigure;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationListener;

public class WorkflowStartupBanner implements ApplicationListener<ApplicationReadyEvent> {

    private static final Logger log = LoggerFactory.getLogger(WorkflowStartupBanner.class);

    private final WorkflowProperties properties;

    public WorkflowStartupBanner(WorkflowProperties properties) {
        this.properties = properties;
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        String port = resolvePort(event.getApplicationContext());
        String base = "http://localhost:" + port;

        WorkflowProperties.Instance inst = properties.getInstance();
        log.info("\n" +
                "=======================================================\n" +
                "         Beeline Workflow Engine - STARTED             \n" +
                "=======================================================\n" +
                "  UI Dashboard    : {}/workflow/ui/index.html\n" +
                "  REST API        : {}/workflow\n" +
                "  Instance ID     : {}\n" +
                "  Multi-instance  : {}\n" +
                "-------------------------------------------------------\n" +
                "  worker-pool-size            = {}\n" +
                "  poll-interval-ms            = {}\n" +
                "  lock-timeout-seconds        = {}\n" +
                "  retry-poll-interval-ms      = {}\n" +
                "  timeout-watcher-interval-ms = {}\n" +
                "=======================================================",
                base, base,
                inst.getId(),
                inst.isMultiInstance(),
                properties.getWorkerPoolSize(),
                properties.getPollIntervalMs(),
                properties.getLockTimeoutSeconds(),
                properties.getRetryPollIntervalMs(),
                properties.getTimeoutWatcherIntervalMs());
    }

    private String resolvePort(ApplicationContext ctx) {
        try {
            Object webServer = ctx.getClass().getMethod("getWebServer").invoke(ctx);
            Object port = webServer.getClass().getMethod("getPort").invoke(webServer);
            return String.valueOf(port);
        } catch (Exception e) {
            return "?";
        }
    }
}
