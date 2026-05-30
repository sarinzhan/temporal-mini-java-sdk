package com.beeline.workflow.engine.replay;

import com.beeline.workflow.core.model.Event;
import com.beeline.workflow.core.model.EventType;
import com.beeline.workflow.engine.codec.PayloadCodec;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReplayStateImplTest {

    private final ObjectMapper mapper = JsonMapper.builder().build();
    private final PayloadCodec codec = new PayloadCodec(mapper);

    private static Event event(EventType type, Integer seq, String payload) {
        Event e = new Event();
        e.setWorkflowId(1L);
        e.setEventType(type);
        e.setCommandType("ACTIVITY");
        e.setSeq(seq);
        e.setPayload(payload);
        return e;
    }

    private ReplayStateImpl stateOf(List<Event> history) {
        return new ReplayStateImpl(new HistoryCursor(1L, history, mapper), codec);
    }

    @Test
    void completedActivityIsReplayedAsCompleted() {
        ReplayStateImpl state = stateOf(List.of(
                event(EventType.ACTIVITY_STARTED, 1, "{\"attempt\":1}"),
                event(EventType.ACTIVITY_COMPLETED, 1, codec.encodeActivityResult("done", 1))));

        Optional<ActivityReplay> replay = state.findActivityResult(1);

        ActivityReplay.Completed completed = assertInstanceOf(ActivityReplay.Completed.class, replay.orElseThrow());
        assertEquals("done", codec.decodeActivityResult(completed.payload(), String.class));
    }

    @Test
    void failedActivityIsReplayedAsFailedWithAttempt() {
        ReplayStateImpl state = stateOf(List.of(
                event(EventType.ACTIVITY_STARTED, 1, "{\"attempt\":1}"),
                event(EventType.ACTIVITY_RETRY_SCHEDULED, 1, "{\"attempt\":1}"),
                event(EventType.ACTIVITY_STARTED, 1, "{\"attempt\":2}"),
                event(EventType.ACTIVITY_FAILED, 1, codec.encodeActivityFailed(2, "boom"))));

        ActivityReplay.Failed failed = assertInstanceOf(ActivityReplay.Failed.class,
                state.findActivityResult(1).orElseThrow());
        assertEquals(2, failed.attempt());
    }

    @Test
    void inProgressActivityHasNoTerminalReplay() {
        // Only STARTED / RETRY_SCHEDULED recorded — replay must re-run the next attempt.
        ReplayStateImpl state = stateOf(List.of(
                event(EventType.ACTIVITY_STARTED, 1, "{\"attempt\":1}"),
                event(EventType.ACTIVITY_RETRY_SCHEDULED, 1, "{\"attempt\":1}")));

        assertTrue(state.findActivityResult(1).isEmpty());
    }

    @Test
    void countsUsedAttemptsFromStartedMarkers() {
        ReplayStateImpl state = stateOf(List.of(
                event(EventType.ACTIVITY_STARTED, 1, "{\"attempt\":1}"),
                event(EventType.ACTIVITY_RETRY_SCHEDULED, 1, "{\"attempt\":1}"),
                event(EventType.ACTIVITY_STARTED, 1, "{\"attempt\":2}")));

        assertEquals(2, state.countUsedActivityAttempts(1));
    }
}
