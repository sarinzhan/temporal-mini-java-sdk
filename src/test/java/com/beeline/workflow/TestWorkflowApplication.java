package com.beeline.workflow;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Test-only bootstrap. The library itself ships no {@code main()} / {@code @SpringBootApplication} —
 * it is wired entirely through {@code WorkflowAutoConfiguration} (see
 * {@code META-INF/spring/...AutoConfiguration.imports}) so consumers stay in control of their own
 * application context. This class only exists to give {@code @SpringBootTest} a
 * {@code @SpringBootConfiguration} to discover and to component-scan the integration-test workflows
 * under {@code com.beeline.workflow.it.support}.
 */
@SpringBootApplication
public class TestWorkflowApplication {
}
