package com.beeline.workflow.it;

import com.beeline.workflow.core.model.EventType;
import com.beeline.workflow.core.model.WorkflowInstance;
import com.beeline.workflow.it.support.Scenario;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActivityRetryIT extends AbstractWorkflowIntegrationTest {

    @Test
    void retriesUntilActivitySucceeds() {
        String key = uniqueKey("retry");
        // Fail the first 2 attempts, succeed on the 3rd; budget of 5 leaves room.
        Long id = start(Scenario.of(key, "flaky").withFailTimes(2).withMaxAttempts(5));

        WorkflowInstance wf = awaitCompleted(id);

        assertTrue(wf.getResult().contains("OK-" + key + "-3"), "third attempt's result is returned");
        assertEquals(3, activities.invocationCount(key, "flaky"), "ran 3 times (2 failed + 1 success)");

        // Two failed attempts parked the turn -> two retry-scheduled markers; exactly one completion.
        assertEquals(2, countEvents(id, EventType.ACTIVITY_RETRY_SCHEDULED));
        assertEquals(1, countEvents(id, EventType.ACTIVITY_COMPLETED));
        assertEquals(0, countEvents(id, EventType.ACTIVITY_FAILED));
        assertEquals(1, countEvents(id, EventType.WORKFLOW_COMPLETED));
        // Each attempt records a STARTED marker.
        assertEquals(3, countEvents(id, EventType.ACTIVITY_STARTED));
    }

    @Test
    void succeedsOnFinalAllowedAttempt() {
        String key = uniqueKey("retry-edge");
        // maxAttempts=3 means attempts 1,2 retry and attempt 3 is the last — make it the success.
        Long id = start(Scenario.of(key, "flaky").withFailTimes(2).withMaxAttempts(3));

        awaitCompleted(id);

        assertEquals(3, activities.invocationCount(key, "flaky"));
        assertEquals(2, countEvents(id, EventType.ACTIVITY_RETRY_SCHEDULED));
    }
}
