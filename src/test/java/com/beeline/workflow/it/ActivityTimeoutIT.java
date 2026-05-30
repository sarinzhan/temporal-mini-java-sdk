package com.beeline.workflow.it;

import com.beeline.workflow.core.model.WorkflowInstance;
import com.beeline.workflow.it.support.Scenario;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActivityTimeoutIT extends AbstractWorkflowIntegrationTest {

    @Test
    void timesOutAndFailsWhenNoRetriesLeft() {
        String key = uniqueKey("timeout-fail");
        // First (and only) attempt sleeps 3s but the start-to-close timeout is 300ms.
        Long id = start(Scenario.of(key, "timeout")
                .withSleepMs(3000)
                .withTimeoutMs(300)
                .withMaxAttempts(1));

        WorkflowInstance wf = awaitFailed(id);

        assertNotNull(wf.getError());
        assertTrue(wf.getError().toLowerCase().contains("timed out"),
                "error should mention the timeout, was: " + wf.getError());
        assertEquals(1, activities.invocationCount(key, "slow"));
    }

    @Test
    void recoversAfterATimeoutWhenRetryBudgetRemains() {
        String key = uniqueKey("timeout-recover");
        // Attempt 1 sleeps past the timeout; attempt 2 doesn't sleep and succeeds.
        Long id = start(Scenario.of(key, "timeout")
                .withSleepMs(3000)
                .withTimeoutMs(300)
                .withMaxAttempts(3));

        WorkflowInstance wf = awaitCompleted(id);

        assertTrue(wf.getResult().contains("SLOW-2"), "second attempt completes");
        assertEquals(2, activities.invocationCount(key, "slow"), "timed-out attempt + successful retry");
    }
}
