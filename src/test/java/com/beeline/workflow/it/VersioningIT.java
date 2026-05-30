package com.beeline.workflow.it;

import com.beeline.workflow.core.model.EventType;
import com.beeline.workflow.core.model.WorkflowInstance;
import com.beeline.workflow.it.support.Scenario;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end regression for {@code Workflow.getVersion}. A version marker is keyed by changeId and
 * must not occupy the per-command seq sequence; otherwise an activity placed after getVersion drifts
 * onto the marker's seq during replay and the engine reports a (false) non-determinism failure.
 */
class VersioningIT extends AbstractWorkflowIntegrationTest {

    @Test
    void getVersionReplaysDeterministicallyAcrossRetries() {
        String key = uniqueKey("version");
        // The flaky activity fails twice before succeeding, so the whole workflow (getVersion included)
        // replays at least twice. With the bug this fails the workflow with NonDeterminismException.
        Long id = start(Scenario.of(key, "version").withFailTimes(2).withMaxAttempts(5));

        WorkflowInstance wf = awaitCompleted(id);

        assertTrue(wf.getResult().contains("v2/"), "a new workflow takes the new (max) version: " + wf.getResult());
        assertEquals(3, activities.invocationCount(key, "flaky"), "activity ran 3 times (2 failed + 1 success)");

        // Exactly one marker despite multiple replays, and the workflow completed (no non-determinism).
        assertEquals(1, countEvents(id, EventType.VERSION_MARKER));
        assertEquals(1, countEvents(id, EventType.WORKFLOW_COMPLETED));
        assertEquals(0, countEvents(id, EventType.WORKFLOW_FAILED));
        assertEquals(2, countEvents(id, EventType.ACTIVITY_RETRY_SCHEDULED));
    }
}
