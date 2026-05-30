package com.beeline.workflow.it;

import com.beeline.workflow.core.model.EventType;
import com.beeline.workflow.core.model.WorkflowInstance;
import com.beeline.workflow.it.support.Scenario;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkflowCodeFailureIT extends AbstractWorkflowIntegrationTest {

    @Test
    void exceptionThrownByWorkflowCodeFailsTheWorkflow() {
        String key = uniqueKey("wf-throw");
        Long id = start(Scenario.of(key, "throwInWorkflow"));

        WorkflowInstance wf = awaitFailed(id);

        assertNotNull(wf.getError());
        assertTrue(wf.getError().contains("workflow code blew up"));
        assertEquals(1, countEvents(id, EventType.WORKFLOW_FAILED));
        assertEquals(1, countEvents(id, EventType.WORKFLOW_TASK_FAILED));
        assertEquals(0, countEvents(id, EventType.WORKFLOW_COMPLETED));
        // No activity ever started — the failure happened in the workflow body itself.
        assertEquals(0, countEvents(id, EventType.ACTIVITY_STARTED));
    }
}
