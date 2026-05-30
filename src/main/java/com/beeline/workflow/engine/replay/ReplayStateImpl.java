package com.beeline.workflow.engine.replay;

import com.beeline.workflow.core.model.Event;
import com.beeline.workflow.core.model.EventType;
import com.beeline.workflow.engine.codec.PayloadCodec;

import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

public final class ReplayStateImpl implements ReplayState {

    private static final Set<EventType> ACTIVITY_TERMINAL = Set.of(
            EventType.ACTIVITY_COMPLETED, EventType.ACTIVITY_FAILED);

    private final HistoryCursor cursor;
    private final PayloadCodec codec;

    public ReplayStateImpl(HistoryCursor cursor, PayloadCodec codec) {
        this.cursor = cursor;
        this.codec = codec;
    }

    @Override public int nextSeq() { return cursor.nextSeq(); }
    @Override public int currentSeq() { return cursor.currentSeq(); }
    @Override public boolean isInReplay() { return cursor.isInReplay(); }
    @Override public HistoryCursor cursor() { return cursor; }

    @Override
    public void assertCommandTypeMatches(int seq, CommandType expected) {
        // findCompletion already throws NonDeterminismException on command-type drift.
        cursor.findCompletion(seq, expected, ACTIVITY_TERMINAL);
    }

    @Override
    public Optional<ActivityReplay> findActivityResult(int seq) {
        Event latest = cursor.latestEventForSeq(seq).orElse(null);
        if (latest == null) return Optional.empty();
        EventType t = latest.getEventType();
        if (t == EventType.ACTIVITY_COMPLETED) {
            return Optional.of(new ActivityReplay.Completed(latest.getPayload()));
        }
        if (t == EventType.ACTIVITY_FAILED) {
            String reason = latest.getPayload() != null ? latest.getPayload() : "activity failed";
            int attempt = highestRecordedAttempt(seq);
            return Optional.of(new ActivityReplay.Failed(reason, attempt));
        }
        return Optional.empty();
    }

    @Override
    public Optional<Event> latestActivityEvent(int seq) {
        return cursor.latestEventForSeq(seq);
    }

    @Override
    public int countUsedActivityAttempts(int seq) {
        int n = 0;
        for (Event e : cursor.eventsForSeq(seq)) {
            if (e.getEventType() == EventType.ACTIVITY_STARTED) n++;
        }
        return n;
    }

    @Override
    public int highestRecordedAttempt(int seq) {
        int n = 0;
        for (Event e : cursor.eventsForSeq(seq)) {
            EventType t = e.getEventType();
            if (t == EventType.ACTIVITY_STARTED
                    || t == EventType.ACTIVITY_RETRY_SCHEDULED
                    || t == EventType.ACTIVITY_FAILED) {
                n = Math.max(n, codec.extractInt(e.getPayload(), "attempt", n));
            }
        }
        return n;
    }

    @Override
    public Optional<String> findSideEffectPayload(int seq) {
        return cursor.findCompletion(seq, CommandType.SIDE_EFFECT,
                Set.of(EventType.SIDE_EFFECT_RECORDED))
                .map(Event::getPayload);
    }

    @Override
    public OptionalInt findVersion(String changeId) {
        return cursor.findVersionMarker(changeId);
    }
}
