package com.beeline.workflow.engine.replay;

import com.beeline.workflow.core.model.Event;
import com.beeline.workflow.core.model.EventType;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

/**
 * Walks the history of events for a single workflow during replay. Hands out
 * monotonic seq numbers for each command (activity, sideEffect, version), and lets
 * the engine ask "is this command already complete in history?".
 *
 * <p>Not thread-safe — a workflow turn runs on a single worker thread.
 */
public final class HistoryCursor {

    private final Long workflowId;
    private final List<Event> history;
    private final ObjectMapper objectMapper;
    private int seq = 0;
    private int maxHistorySeq;

    public HistoryCursor(Long workflowId, List<Event> history, ObjectMapper objectMapper) {
        this.workflowId = workflowId;
        this.history = history;
        this.objectMapper = objectMapper;
        int max = 0;
        for (Event e : history) {
            if (e.getSeq() != null && e.getSeq() > max) max = e.getSeq();
        }
        this.maxHistorySeq = max;
    }

    public Long getWorkflowId() { return workflowId; }

    public int nextSeq() { return ++seq; }

    public int currentSeq() { return seq; }

    /** True iff the command we just dispatched is being re-played from existing history. */
    public boolean isInReplay() { return seq <= maxHistorySeq; }

    /**
     * True iff recorded command history exists at a seq strictly higher than the cursor's current
     * position — i.e. the workflow already ran past this point in an earlier turn. Used by
     * {@code getVersion} to tell an old workflow that predates the change (→ DEFAULT_VERSION) apart
     * from new code reaching a fresh version point at the tip of history (→ record the marker).
     *
     * <p>Unlike {@link #isInReplay()} this uses a strict {@code <}: at the very start of a brand-new
     * workflow ({@code seq == maxHistorySeq == 0}) there is no history ahead, so a first-statement
     * {@code getVersion} correctly records a marker instead of being misread as "replaying".
     */
    public boolean hasCommandsAhead() { return seq < maxHistorySeq; }

    /** True iff history has any event for this seq (regardless of type). */
    public boolean hasAnyEventForSeq(int targetSeq) {
        for (Event e : history) {
            if (e.getSeq() != null && e.getSeq() == targetSeq) return true;
        }
        return false;
    }

    /**
     * Find the *terminal* completion event for this (seq, commandType). Terminal types
     * differ per command kind (e.g. ACTIVITY_COMPLETED / ACTIVITY_FAILED for activities).
     *
     * <p>If an event exists at this seq with a *different* command type, throws
     * {@link NonDeterminismException} — workflow code shape changed.
     */
    public Optional<Event> findCompletion(int targetSeq, CommandType cmdType, Set<EventType> terminalTypes) {
        Event terminal = null;
        for (Event e : history) {
            if (e.getSeq() == null || e.getSeq() != targetSeq) continue;
            String ct = e.getCommandType();
            if (ct != null && !ct.equals(cmdType.name())) {
                throw new NonDeterminismException(
                        "Workflow code changed: seq=" + targetSeq + " was recorded as " + ct +
                        " but workflow now treats it as " + cmdType);
            }
            if (terminalTypes.contains(e.getEventType()) && terminal == null) {
                terminal = e;
            }
        }
        return Optional.ofNullable(terminal);
    }

    /**
     * The most recent event recorded for this seq (history is ordered by id ascending, so the
     * last match wins). Used by the activity executor to tell apart "not yet completed"
     * (e.g. only ACTIVITY_STARTED / ACTIVITY_RETRY_SCHEDULED, so run the next attempt) from
     * terminal (ACTIVITY_COMPLETED → cached result / ACTIVITY_FAILED → re-throw) states.
     */
    public Optional<Event> latestEventForSeq(int targetSeq) {
        Event latest = null;
        for (Event e : history) {
            if (e.getSeq() != null && e.getSeq() == targetSeq) latest = e;
        }
        return Optional.ofNullable(latest);
    }

    /** All events recorded for this seq, in id (chronological) order. */
    public List<Event> eventsForSeq(int targetSeq) {
        List<Event> out = new java.util.ArrayList<>();
        for (Event e : history) {
            if (e.getSeq() != null && e.getSeq() == targetSeq) out.add(e);
        }
        return out;
    }

    /** Find an event by (seq, exact type). Used to avoid double-writing markers. */
    public Optional<Event> findBySeqAndType(int targetSeq, EventType type) {
        for (Event e : history) {
            if (e.getSeq() != null && e.getSeq() == targetSeq && e.getEventType() == type) {
                return Optional.of(e);
            }
        }
        return Optional.empty();
    }

    /**
     * Find a {@code VERSION_MARKER} for the given changeId. Versions are keyed by changeId
     * globally per workflow, not by seq, so a single marker can satisfy many code paths.
     */
    public OptionalInt findVersionMarker(String changeId) {
        for (Event e : history) {
            if (e.getEventType() != EventType.VERSION_MARKER) continue;
            String payload = e.getPayload();
            if (payload == null) continue;
            try {
                JsonNode node = objectMapper.readTree(payload);
                JsonNode cid = node.get("changeId");
                JsonNode ver = node.get("version");
                if (cid != null && ver != null && changeId.equals(cid.asString())) {
                    return OptionalInt.of(ver.asInt());
                }
            } catch (Exception ignored) {
                // malformed marker — treat as missing
            }
        }
        return OptionalInt.empty();
    }
}
