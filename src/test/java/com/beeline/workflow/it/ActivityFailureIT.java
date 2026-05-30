package com.beeline.workflow.it;

import com.beeline.workflow.core.model.EventType;
import com.beeline.workflow.core.model.WorkflowInstance;
import com.beeline.workflow.it.support.Scenario;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActivityFailureIT extends AbstractWorkflowIntegrationTest {

    @Test
    void failsWorkflowWhenRetriesAreExhausted() {
        String key = uniqueKey("exhaust");
        Long id = start(Scenario.of(key, "alwaysFail").withMaxAttempts(3));

        WorkflowInstance wf = awaitFailed(id);

        assertEquals(3, activities.invocationCount(key, "alwaysFail"), "exactly maxAttempts executions");
        assertNotNull(wf.getError());
        assertTrue(wf.getError().contains("permanent boom"), "error carries the activity cause");

        // The last attempt records the terminal ACTIVITY_FAILED; the first two only park.
        assertEquals(1, countEvents(id, EventType.ACTIVITY_FAILED));
        assertEquals(2, countEvents(id, EventType.ACTIVITY_RETRY_SCHEDULED));
        assertEquals(1, countEvents(id, EventType.WORKFLOW_FAILED));
        assertEquals(0, countEvents(id, EventType.WORKFLOW_COMPLETED));
    }

    @Test
    void nonRetryableExceptionFailsImmediatelyWithoutRetrying() {
        String key = uniqueKey("nonretryable");
        // Budget of 5, but a NonRetryableException must stop after a single attempt.
        Long id = start(Scenario.of(key, "nonRetryable").withMaxAttempts(5));

        WorkflowInstance wf = awaitFailed(id);

        assertEquals(1, activities.invocationCount(key, "nonRetryable"), "no retries for non-retryable");
        assertEquals(0, countEvents(id, EventType.ACTIVITY_RETRY_SCHEDULED), "never scheduled a retry");
        assertEquals(1, countEvents(id, EventType.ACTIVITY_FAILED));
        assertEquals(1, countEvents(id, EventType.WORKFLOW_FAILED));
    }
}
