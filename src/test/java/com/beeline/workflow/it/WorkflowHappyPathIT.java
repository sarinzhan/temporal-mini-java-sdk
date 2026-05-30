package com.beeline.workflow.it;

import com.beeline.workflow.core.model.EventType;
import com.beeline.workflow.core.model.WorkflowInstance;
import com.beeline.workflow.it.support.Scenario;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkflowHappyPathIT extends AbstractWorkflowIntegrationTest {

    @Test
    void completesAndRecordsHistory() {
        String key = uniqueKey("happy");
        Long id = start(Scenario.of(key, "happy"));

        WorkflowInstance wf = awaitCompleted(id);

        assertTrue(wf.getResult().contains("RES-" + key), "result should carry the activity output");
        assertEquals(1, activities.invocationCount(key, "reserve"), "activity runs exactly once");

        // History is the replay contract — assert the lifecycle is recorded once each.
        assertEquals(1, countEvents(id, EventType.WORKFLOW_CREATED));
        assertEquals(1, countEvents(id, EventType.ACTIVITY_STARTED));
        assertEquals(1, countEvents(id, EventType.ACTIVITY_COMPLETED));
        assertEquals(1, countEvents(id, EventType.WORKFLOW_COMPLETED));
        assertEquals(0, countEvents(id, EventType.WORKFLOW_FAILED));
        assertEquals(0, countEvents(id, EventType.ACTIVITY_RETRY_SCHEDULED));
    }

    @Test
    void runsManyWorkflowsConcurrently() {
        int n = 12;
        Long[] ids = new Long[n];
        for (int i = 0; i < n; i++) {
            ids[i] = start(Scenario.of(uniqueKey("concurrent-" + i), "happy"));
        }
        for (Long id : ids) {
            awaitCompleted(id);
        }
    }
}
