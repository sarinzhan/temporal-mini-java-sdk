package com.beeline.workflow.it;

import com.beeline.workflow.core.model.EventType;
import com.beeline.workflow.core.model.WorkflowInstance;
import com.beeline.workflow.it.support.Scenario;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The core durability guarantee: when a workflow replays across turns (here forced by activity
 * retries parking the turn), already-completed commands are served from history and never
 * re-executed. These tests assert that with real side-effect counters, not mocks.
 */
class ReplayDeterminismIT extends AbstractWorkflowIntegrationTest {

    @Test
    void completedActivityIsNotReexecutedOnReplay() {
        String key = uniqueKey("replay");
        // 'reserve' completes turn 1; 'flaky' fails twice, parking + replaying the workflow twice.
        Long id = start(Scenario.of(key, "replay").withFailTimes(2).withMaxAttempts(5));

        WorkflowInstance wf = awaitCompleted(id);

        // Despite 3 turns total, the already-completed 'reserve' ran exactly once.
        assertEquals(1, activities.invocationCount(key, "reserve"),
                "completed activity must be replayed from history, not re-run");
        assertEquals(3, activities.invocationCount(key, "flaky"));
        assertTrue(wf.getResult().contains("RES-" + key));

        // 'reserve' has a single completion across the whole history.
        assertEquals(1, eventsOf(id).stream()
                .filter(e -> e.getEventType() == EventType.ACTIVITY_COMPLETED && "reserve".equals(e.getActivityName()))
                .count());
    }

    @Test
    void sideEffectIsRecordedOnceAndReplayed() {
        String key = uniqueKey("sideEffect");
        // sideEffect produces a value, then the flaky activity forces one replay.
        Long id = start(Scenario.of(key, "sideEffect").withFailTimes(1).withMaxAttempts(5));

        WorkflowInstance wf = awaitCompleted(id);

        List<String> produced = activities.sideEffectValues(key);
        assertEquals(1, produced.size(), "sideEffect body must execute exactly once across replays");
        assertEquals(1, countEvents(id, EventType.SIDE_EFFECT_RECORDED));
        // The workflow returns the recorded sideEffect value — stable across the replay.
        assertTrue(wf.getResult().contains(produced.get(0)),
                "returned value must equal the once-recorded sideEffect");
    }
}
