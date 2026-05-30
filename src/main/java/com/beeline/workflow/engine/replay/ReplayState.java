package com.beeline.workflow.engine.replay;

import com.beeline.workflow.core.model.Event;

import java.util.Optional;
import java.util.OptionalInt;

/**
 * Domain read-side of the workflow history during a decision turn. Wraps the lower-level
 * {@link HistoryCursor} so command handlers do not deal with raw {@link Event}s.
 */
public interface ReplayState {

    int nextSeq();

    int currentSeq();

    boolean isInReplay();

    /** Look up the recorded outcome of an activity command at this seq. */
    Optional<ActivityReplay> findActivityResult(int seq);

    /** Latest event for an activity seq (any state); for diagnostics / non-determinism guard. */
    Optional<Event> latestActivityEvent(int seq);

    /** Number of attempts already used for this activity seq (orphaned STARTEDs are not counted). */
    int countUsedActivityAttempts(int seq);

    /** Highest {@code attempt} number recorded in payload for this seq, for error messages. */
    int highestRecordedAttempt(int seq);

    /** Decoded payload of the recorded SIDE_EFFECT for this seq, if any. */
    Optional<String> findSideEffectPayload(int seq);

    /** Recorded version for changeId, if VERSION_MARKER for it exists. */
    OptionalInt findVersion(String changeId);

    /** Non-determinism guard: throws if a recorded event at seq has a different command type. */
    void assertCommandTypeMatches(int seq, CommandType expected);

    /** Underlying cursor — exposed so handlers that need to assert determinism keep working. */
    HistoryCursor cursor();
}
