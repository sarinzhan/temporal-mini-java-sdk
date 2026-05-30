package com.beeline.workflow.engine.command.handler;

import com.beeline.workflow.core.api.Workflow;
import com.beeline.workflow.core.model.Event;
import com.beeline.workflow.core.model.EventType;
import com.beeline.workflow.engine.codec.PayloadCodec;
import com.beeline.workflow.engine.command.CommandContext;
import com.beeline.workflow.engine.command.VersionCommand;
import com.beeline.workflow.engine.replay.CommandType;
import com.beeline.workflow.engine.replay.EventLogImpl;
import com.beeline.workflow.engine.replay.HistoryCursor;
import com.beeline.workflow.engine.replay.ReplayStateImpl;
import com.beeline.workflow.engine.replay.TaskLease;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VersionCommandHandlerTest {

    private final ObjectMapper mapper = JsonMapper.builder().build();
    private final PayloadCodec codec = new PayloadCodec(mapper);
    private final VersionCommandHandler handler = new VersionCommandHandler();

    private Event activity(int seq) {
        Event e = new Event();
        e.setWorkflowId(1L);
        e.setEventType(EventType.ACTIVITY_COMPLETED);
        e.setCommandType(CommandType.ACTIVITY.name());
        e.setSeq(seq);
        e.setPayload(codec.encodeActivityResult("r" + seq, 1));
        return e;
    }

    private Event versionMarker(String changeId, int version) {
        Event e = new Event();
        e.setWorkflowId(1L);
        e.setEventType(EventType.VERSION_MARKER);
        e.setCommandType(CommandType.VERSION.name());
        e.setSeq(null);  // markers carry no seq
        e.setPayload(codec.encodeVersionMarker(changeId, version));
        return e;
    }

    private static final class Fixture {
        final ReplayStateImpl state;
        final EventLogImpl eventLog;
        final CommandContext ctx;

        Fixture(ReplayStateImpl state, EventLogImpl eventLog, CommandContext ctx) {
            this.state = state;
            this.eventLog = eventLog;
            this.ctx = ctx;
        }
    }

    private Fixture fixtureOf(List<Event> history) {
        ReplayStateImpl state = new ReplayStateImpl(new HistoryCursor(1L, history, mapper), codec);
        EventLogImpl eventLog = new EventLogImpl(1L, TaskLease.ALWAYS_OWNED, codec);
        CommandContext ctx = new CommandContext(1L, 1L, state, eventLog, TaskLease.ALWAYS_OWNED, codec, null);
        return new Fixture(state, eventLog, ctx);
    }

    @Test
    void firstEverGetVersionRecordsMarkerAndDoesNotConsumeSeq() {
        // Brand-new workflow, getVersion is the first statement: empty command history.
        Fixture f = fixtureOf(new ArrayList<>());

        int v = (int) handler.handle(new VersionCommand("v1", 1, 3), f.ctx);

        assertEquals(3, v, "a new workflow must get the new (max) version");
        assertEquals(0, f.state.currentSeq(), "getVersion must not consume a seq slot");
        assertEquals(1, f.eventLog.bufferedEvents().size());
        Event marker = f.eventLog.bufferedEvents().get(0);
        assertEquals(EventType.VERSION_MARKER, marker.getEventType());
        assertNull(marker.getSeq(), "version markers must not carry a seq");
    }

    @Test
    void replayWithExistingMarkerReturnsRecordedVersionWithoutRewriting() {
        Fixture f = fixtureOf(List.of(versionMarker("v1", 3)));

        int v = (int) handler.handle(new VersionCommand("v1", 1, 3), f.ctx);

        assertEquals(3, v);
        assertEquals(0, f.state.currentSeq());
        assertTrue(f.eventLog.bufferedEvents().isEmpty(), "no second marker on replay");
    }

    @Test
    void oldWorkflowThatPredatesTheChangeGetsDefaultVersion() {
        // Workflow already ran two activities in earlier turns; getVersion is newly inserted code.
        Fixture f = fixtureOf(List.of(activity(1), activity(2)));
        f.state.nextSeq();  // replay activity#1 → cursor now at seq 1, with history ahead (seq 2)

        int v = (int) handler.handle(new VersionCommand("v1", 1, 3), f.ctx);

        assertEquals(Workflow.DEFAULT_VERSION, v);
        assertEquals(1, f.state.currentSeq(), "must not consume a seq");
        assertTrue(f.eventLog.bufferedEvents().isEmpty());
    }

    @Test
    void getVersionBetweenActivitiesKeepsSeqAligned() {
        // Regression: getVersion must not desynchronise the seq counter between the original turn and
        // replay. History was produced by a turn that ran activity#1, getVersion (marker, no seq),
        // activity#2. On replay every command must land on the same seq it had originally.
        Fixture f = fixtureOf(List.of(activity(1), versionMarker("v1", 3), activity(2)));

        int s1 = f.state.nextSeq();                     // activity#1 → seq 1
        assertDoesNotThrow(() -> f.state.assertCommandTypeMatches(s1, CommandType.ACTIVITY));

        int v = (int) handler.handle(new VersionCommand("v1", 1, 3), f.ctx);
        assertEquals(3, v);

        int s2 = f.state.nextSeq();                     // activity#2 must still be seq 2
        assertEquals(2, s2);
        assertDoesNotThrow(() -> f.state.assertCommandTypeMatches(s2, CommandType.ACTIVITY),
                "activity#2 must align with the recorded ACTIVITY at seq 2, not the version marker");
    }

    @Test
    void rejectsInvalidRangeAndTooOldWorkflow() {
        Fixture f1 = fixtureOf(new ArrayList<>());
        assertThrows(IllegalArgumentException.class,
                () -> handler.handle(new VersionCommand("v1", 5, 3), f1.ctx));

        Fixture f2 = fixtureOf(List.of(versionMarker("v1", 1)));
        assertThrows(IllegalStateException.class,
                () -> handler.handle(new VersionCommand("v1", 2, 3), f2.ctx));
    }
}
