package com.beeline.workflow.engine.replay;

/**
 * Domain projection of an activity's recorded terminal outcome at a given seq.
 * Carries the raw payload so the codec can decode the result lazily.
 */
public sealed interface ActivityReplay {

    /** Activity completed previously; raw payload contains the JSONB to decode. */
    record Completed(String payload) implements ActivityReplay {}

    /** Activity terminally failed previously; payload carries the reason text. */
    record Failed(String reason, int attempt) implements ActivityReplay {}
}
