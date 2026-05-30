package com.beeline.workflow.engine.replay;

import com.beeline.workflow.core.model.Event;
import com.beeline.workflow.core.model.EventType;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HistoryCursorTest {

    private final ObjectMapper mapper = JsonMapper.builder().build();

    private static Event event(EventType type, String commandType, Integer seq, String payload) {
        Event e = new Event();
        e.setWorkflowId(1L);
        e.setEventType(type);
        e.setCommandType(commandType);
        e.setSeq(seq);
        e.setPayload(payload);
        return e;
    }

    @Test
    void nextSeqIsMonotonic() {
        HistoryCursor cursor = new HistoryCursor(1L, List.of(), mapper);
        assertEquals(1, cursor.nextSeq());
        assertEquals(2, cursor.nextSeq());
        assertEquals(3, cursor.nextSeq());
        assertEquals(3, cursor.currentSeq());
    }

    @Test
    void isInReplayWhileSeqWithinRecordedHistory() {
        List<Event> history = List.of(
                event(EventType.ACTIVITY_COMPLETED, "ACTIVITY", 1, "{}"),
                event(EventType.ACTIVITY_COMPLETED, "ACTIVITY", 2, "{}"));
        HistoryCursor cursor = new HistoryCursor(1L, history, mapper);

        cursor.nextSeq(); // 1
        assertTrue(cursor.isInReplay());
        cursor.nextSeq(); // 2
        assertTrue(cursor.isInReplay());
        cursor.nextSeq(); // 3 — beyond history, this is new work
        assertFalse(cursor.isInReplay());
    }

    @Test
    void commandTypeDriftIsDetectedAsNonDeterminism() {
        // History recorded seq=1 as a SIDE_EFFECT, but the replayed code now asks for an ACTIVITY.
        List<Event> history = List.of(
                event(EventType.SIDE_EFFECT_RECORDED, "SIDE_EFFECT", 1, "{}"));
        HistoryCursor cursor = new HistoryCursor(1L, history, mapper);

        assertThrows(NonDeterminismException.class, () ->
                cursor.findCompletion(1, CommandType.ACTIVITY,
                        Set.of(EventType.ACTIVITY_COMPLETED, EventType.ACTIVITY_FAILED)));
    }

    @Test
    void findCompletionReturnsTerminalEvent() {
        List<Event> history = List.of(
                event(EventType.ACTIVITY_STARTED, "ACTIVITY", 1, "{\"attempt\":1}"),
                event(EventType.ACTIVITY_COMPLETED, "ACTIVITY", 1, "{\"result\":42}"));
        HistoryCursor cursor = new HistoryCursor(1L, history, mapper);

        var found = cursor.findCompletion(1, CommandType.ACTIVITY,
                Set.of(EventType.ACTIVITY_COMPLETED, EventType.ACTIVITY_FAILED));

        assertTrue(found.isPresent());
        assertEquals(EventType.ACTIVITY_COMPLETED, found.get().getEventType());
    }

    @Test
    void latestEventForSeqReturnsMostRecentlyAppended() {
        List<Event> history = List.of(
                event(EventType.ACTIVITY_STARTED, "ACTIVITY", 1, "a"),
                event(EventType.ACTIVITY_RETRY_SCHEDULED, "ACTIVITY", 1, "b"),
                event(EventType.ACTIVITY_STARTED, "ACTIVITY", 1, "c"));
        HistoryCursor cursor = new HistoryCursor(1L, history, mapper);

        assertEquals("c", cursor.latestEventForSeq(1).orElseThrow().getPayload());
    }

    @Test
    void findsVersionMarkerByChangeId() {
        List<Event> history = List.of(
                event(EventType.VERSION_MARKER, "VERSION", null, "{\"changeId\":\"add-tax\",\"version\":2}"));
        HistoryCursor cursor = new HistoryCursor(1L, history, mapper);

        assertEquals(2, cursor.findVersionMarker("add-tax").orElseThrow());
        assertTrue(cursor.findVersionMarker("missing").isEmpty());
    }
}
