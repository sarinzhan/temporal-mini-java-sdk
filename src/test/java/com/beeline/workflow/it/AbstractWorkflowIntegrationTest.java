package com.beeline.workflow.it;

import com.beeline.workflow.core.model.Event;
import com.beeline.workflow.core.model.EventType;
import com.beeline.workflow.core.model.WorkflowInstance;
import com.beeline.workflow.core.model.WorkflowStatus;
import com.beeline.workflow.it.support.Scenario;
import com.beeline.workflow.it.support.ScenarioWorkflow;
import com.beeline.workflow.it.support.TestActivities;
import com.beeline.workflow.persistence.repository.EventRepository;
import com.beeline.workflow.persistence.repository.WorkflowRepository;
import com.beeline.workflow.spring.api.WorkflowClient;
import com.beeline.workflow.spring.api.WorkflowHandle;
import org.junit.jupiter.api.BeforeAll;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * Base for engine integration tests. Boots the full Spring context (worker loop, wakeup scheduler
 * and timeout watcher all run on their real {@code @Scheduled} cadence) against a real PostgreSQL
 * in a Testcontainers container, so replay, retries, fencing and the JSONB schema are exercised
 * exactly as in production.
 *
 * <p>The container is a JVM-wide singleton and the Spring context is cached across every subclass
 * (they share identical config), so the whole IT suite starts one Postgres and one context. Tests
 * isolate themselves with a {@link #uniqueKey} rather than by wiping the database.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
public abstract class AbstractWorkflowIntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static {
        // Singleton-container pattern: started once, reused by every subclass, torn down at JVM exit.
        POSTGRES.start();
        // Create the schema up front — production assumes schema.sql is applied before the app boots
        // (the engine's ApplicationRunner initializer only runs after refresh, too late for beans
        // that touch the DB in @PostConstruct). Doing it here mirrors the production deployment.
        applySchema();
    }

    private static void applySchema() {
        try (Connection conn = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            ScriptUtils.executeSqlScript(conn, new ClassPathResource("schema.sql"));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to apply schema.sql to test container", e);
        }
    }

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        // Tighten every scheduler so retries/wakeups resolve in hundreds of ms, not seconds.
        registry.add("workflow.poll-interval-ms", () -> "100");
        registry.add("workflow.retry-poll-interval-ms", () -> "100");
        registry.add("workflow.timeout-watcher-interval-ms", () -> "500");
        registry.add("workflow.lease-renew-interval-ms", () -> "5000");
        registry.add("workflow.lock-timeout-seconds", () -> "30");
    }

    protected static final Duration DEFAULT_AWAIT = Duration.ofSeconds(20);
    private static final Set<WorkflowStatus> TERMINAL =
            EnumSet.of(WorkflowStatus.COMPLETED, WorkflowStatus.FAILED, WorkflowStatus.CANCELLED);

    @Autowired protected WorkflowClient workflowClient;
    @Autowired protected WorkflowRepository workflowRepository;
    @Autowired protected EventRepository eventRepository;
    @Autowired protected TestActivities activities;

    @BeforeAll
    static void containerRunning() {
        if (!POSTGRES.isRunning()) {
            fail("Postgres test container failed to start");
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /** A key unique to this test invocation, namespacing activity counters and avoiding cross-talk. */
    protected String uniqueKey(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    /** Start the scenario workflow and return its instance id. */
    protected Long start(Scenario scenario) {
        WorkflowHandle<String> handle = workflowClient.start(ScenarioWorkflow.class, scenario);
        return handle.getInstanceId();
    }

    /** Block until the workflow reaches a terminal status (or the timeout fails the test). */
    protected WorkflowInstance awaitTerminal(Long workflowId) {
        return awaitTerminal(workflowId, DEFAULT_AWAIT);
    }

    protected WorkflowInstance awaitTerminal(Long workflowId, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        WorkflowStatus last = null;
        while (System.nanoTime() < deadline) {
            WorkflowInstance wf = workflowRepository.findById(workflowId).orElse(null);
            if (wf != null) {
                last = wf.getStatus();
                if (TERMINAL.contains(last)) {
                    return wf;
                }
            }
            sleep(50);
        }
        return fail("workflow " + workflowId + " did not reach a terminal status within "
                + timeout.toMillis() + "ms (last status=" + last + ")");
    }

    protected WorkflowInstance awaitCompleted(Long workflowId) {
        WorkflowInstance wf = awaitTerminal(workflowId);
        if (wf.getStatus() != WorkflowStatus.COMPLETED) {
            fail("expected COMPLETED but was " + wf.getStatus() + " (error=" + wf.getError() + ")");
        }
        return wf;
    }

    protected WorkflowInstance awaitFailed(Long workflowId) {
        WorkflowInstance wf = awaitTerminal(workflowId);
        if (wf.getStatus() != WorkflowStatus.FAILED) {
            fail("expected FAILED but was " + wf.getStatus());
        }
        return wf;
    }

    protected List<Event> eventsOf(Long workflowId) {
        return eventRepository.findByWorkflowIdOrderByIdAsc(workflowId);
    }

    protected long countEvents(Long workflowId, EventType type) {
        return eventsOf(workflowId).stream().filter(e -> e.getEventType() == type).count();
    }

    protected static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }
}
