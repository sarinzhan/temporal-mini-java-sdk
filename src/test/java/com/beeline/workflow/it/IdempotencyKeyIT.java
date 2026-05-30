package com.beeline.workflow.it;

import com.beeline.workflow.core.model.EventType;
import com.beeline.workflow.core.model.WorkflowInstance;
import com.beeline.workflow.it.support.Scenario;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves {@code Workflow.currentActivityKey()} is visible inside an activity body (the body runs on a
 * separate pool thread) and that the key is identical across every retry/replay of the same activity —
 * the property that makes external side effects effectively-once when the downstream deduplicates.
 */
class IdempotencyKeyIT extends AbstractWorkflowIntegrationTest {

    @Test
    void activityKeyIsVisibleAndStableAcrossRetries() {
        String key = uniqueKey("idem");
        // Fail twice, succeed on the 3rd attempt: the body runs 3 times across 3 turns (replays).
        Long id = start(Scenario.of(key, "idempotencyKey").withFailTimes(2).withMaxAttempts(5));

        WorkflowInstance wf = awaitCompleted(id);
        assertTrue(wf.getResult().contains("OK-" + key + "-3"), "third attempt's result: " + wf.getResult());

        List<String> keys = activities.observedKeys(key);
        assertEquals(3, keys.size(), "key recorded on each of the 3 attempts: " + keys);
        String expected = "wf:" + id + ":1";
        assertTrue(keys.stream().allMatch(expected::equals),
                "idempotency key must be identical (" + expected + ") on every attempt, got " + keys);

        assertEquals(3, countEvents(id, EventType.ACTIVITY_STARTED));
        assertEquals(1, countEvents(id, EventType.ACTIVITY_COMPLETED));
    }
}
