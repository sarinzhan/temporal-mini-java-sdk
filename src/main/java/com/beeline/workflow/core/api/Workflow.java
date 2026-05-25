package com.beeline.workflow.core.api;

import com.beeline.workflow.core.config.ActivityOptions;
import com.beeline.workflow.core.model.EventType;
import com.beeline.workflow.engine.context.WorkflowContextHolder;
import com.beeline.workflow.engine.executor.ActivityExecutor;
import com.beeline.workflow.engine.replay.CommandType;
import com.beeline.workflow.engine.replay.EventSink;
import com.beeline.workflow.engine.replay.HistoryCursor;
import com.beeline.workflow.engine.replay.QueryReplayBlockedException;
import com.beeline.workflow.engine.replay.WorkflowParkedException;
import com.beeline.workflow.engine.stub.ActivityStubFactory;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.Function;
import java.util.function.Supplier;

public final class Workflow {

    private Workflow() {}

    /** Returned by getVersion for workflows that pre-date the change. */
    public static final int DEFAULT_VERSION = -1;

    private static volatile ObjectMapper objectMapper;

    public static void installObjectMapper(ObjectMapper mapper) {
        Workflow.objectMapper = mapper;
    }

    // ── Typed-interface stub (JDK Proxy) ────────────────────────────────────

    public static <T> T newActivityStub(Class<T> activityInterface) {
        return newActivityStub(activityInterface, ActivityOptions.defaultOptions());
    }

    public static <T> T newActivityStub(Class<T> activityInterface, ActivityOptions options) {
        return ActivityStubFactory.createStub(activityInterface, options);
    }

    // ── Functional activity API ─────────────────────────────────────────────

    public static <T> T activity(String name, Supplier<T> fn) {
        return activity(name, ActivityOptions.defaultOptions(), fn);
    }

    @SuppressWarnings("unchecked")
    public static <T> T activity(String name, ActivityOptions options, Supplier<T> fn) {
        WorkflowContextHolder.require();
        ActivityExecutor executor = ActivityStubFactory.requireExecutor();
        return (T) executor.execute(name, options, null, fn::get);
    }

    public static <I, O> O activity(String name, I input, Function<I, O> fn) {
        return activity(name, ActivityOptions.defaultOptions(), input, fn);
    }

    @SuppressWarnings("unchecked")
    public static <I, O> O activity(String name, ActivityOptions options, I input, Function<I, O> fn) {
        WorkflowContextHolder.require();
        ActivityExecutor executor = ActivityStubFactory.requireExecutor();
        return (O) executor.execute(name, options, null, () -> fn.apply(input));
    }

    public static void activity(String name, Runnable fn) {
        activity(name, ActivityOptions.defaultOptions(), fn);
    }

    public static void activity(String name, ActivityOptions options, Runnable fn) {
        WorkflowContextHolder.require();
        ActivityExecutor executor = ActivityStubFactory.requireExecutor();
        executor.execute(name, options, void.class, () -> { fn.run(); return null; });
    }

    // ── Determinism helpers ─────────────────────────────────────────────────

    /**
     * Execute {@code fn} exactly once and persist its result; on replay returns the persisted value
     * without re-running {@code fn}. Use for non-deterministic reads (random IDs, current time, etc).
     */
    @SuppressWarnings("unchecked")
    public static <T> T sideEffect(Class<T> type, Supplier<T> fn) {
        WorkflowContext ctx = WorkflowContextHolder.require();
        HistoryCursor cursor = ctx.getHistoryCursor();
        ObjectMapper om = requireObjectMapper();
        int seq = cursor.nextSeq();

        Optional<com.beeline.workflow.core.model.Event> recorded =
                cursor.findCompletion(seq, CommandType.SIDE_EFFECT,
                        java.util.Set.of(EventType.SIDE_EFFECT_RECORDED));
        if (recorded.isPresent()) {
            try {
                var node = om.readTree(recorded.get().getPayload());
                var resultNode = node.get("result");
                if (resultNode == null || resultNode.isNull()) return null;
                return (T) om.readValue(om.writeValueAsString(resultNode), om.constructType(type));
            } catch (Exception e) {
                throw new RuntimeException("Failed to deserialize sideEffect result", e);
            }
        }

        if (cursor.isQueryMode()) {
            throw new QueryReplayBlockedException("sideEffect not recorded in history (seq=" + seq + ")");
        }

        T value = fn.get();
        String payload;
        try {
            String resultJson = value == null ? "null" : om.writeValueAsString(value);
            payload = "{\"result\":" + resultJson + "}";
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize sideEffect result", e);
        }
        ctx.getEventSink().append(EventType.SIDE_EFFECT_RECORDED, CommandType.SIDE_EFFECT, seq, null, payload);
        return value;
    }

    /**
     * Returns a version for a code-change point. On the first run for this changeId in a
     * workflow, writes a VERSION_MARKER with version={@code maxSupported} and returns it.
     * On subsequent replays returns the recorded version. Workflows that pre-date the
     * change return {@link #DEFAULT_VERSION}, allowing the old code path to be taken.
     */
    public static int getVersion(String changeId, int minSupported, int maxSupported) {
        if (minSupported > maxSupported) {
            throw new IllegalArgumentException("minSupported > maxSupported: " + minSupported + " > " + maxSupported);
        }
        WorkflowContext ctx = WorkflowContextHolder.require();
        HistoryCursor cursor = ctx.getHistoryCursor();

        OptionalInt existing = cursor.findVersionMarker(changeId);
        if (existing.isPresent()) {
            int v = existing.getAsInt();
            if (v < minSupported) {
                throw new IllegalStateException(
                        "Workflow on too-old version for changeId=" + changeId + ": " + v + " < " + minSupported);
            }
            return v;
        }

        // No marker. If we're in replay, this is an old workflow that pre-dates the call.
        if (cursor.isInReplay()) {
            return DEFAULT_VERSION;
        }
        if (cursor.isQueryMode()) {
            // No marker yet and we're past history — treat as default for queries.
            return DEFAULT_VERSION;
        }

        int seq = cursor.nextSeq();
        ObjectMapper om = requireObjectMapper();
        String payload;
        try {
            payload = om.writeValueAsString(java.util.Map.of(
                    "changeId", changeId,
                    "version", maxSupported));
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize VERSION_MARKER payload", e);
        }
        ctx.getEventSink().append(EventType.VERSION_MARKER, CommandType.VERSION, seq, null, payload);
        return maxSupported;
    }

    private static ObjectMapper requireObjectMapper() {
        ObjectMapper om = objectMapper;
        if (om == null) {
            throw new IllegalStateException(
                    "ObjectMapper is not installed. The Spring autoconfigure must call Workflow.installObjectMapper().");
        }
        return om;
    }

    // ── Suspending commands: sleep, await ───────────────────────────────────

    /**
     * Suspend the workflow for at least {@code duration}. The current decision turn ends,
     * the worker is freed, and the {@code WakeupScheduler} re-enqueues the workflow once
     * the timer fires.
     */
    public static void sleep(Duration duration) {
        WorkflowContext ctx = WorkflowContextHolder.require();
        HistoryCursor cursor = ctx.getHistoryCursor();
        int seq = cursor.nextSeq();

        if (cursor.findCompletion(seq, CommandType.TIMER,
                java.util.Set.of(EventType.TIMER_FIRED)).isPresent()) {
            return;
        }
        if (cursor.isQueryMode()) {
            throw new QueryReplayBlockedException("workflow parked at sleep seq=" + seq);
        }

        if (cursor.findBySeqAndType(seq, EventType.TIMER_STARTED).isEmpty()) {
            Instant fireAt = Instant.now().plus(duration);
            ctx.getEventSink().append(EventType.TIMER_STARTED, CommandType.TIMER, seq, null,
                    "{\"fireAt\":\"" + fireAt + "\"}");
            ctx.getWakeupRegistrar().registerTimer(seq, fireAt);
        }
        throw new WorkflowParkedException(WorkflowParkedException.Kind.TIMER, seq);
    }

    /**
     * Block until {@code condition} returns true, optionally with a timeout.
     * Suspends the workflow on each false evaluation; resumes when a signal arrives
     * or when the timeout elapses.
     *
     * @return true if condition was satisfied; false if timed out
     */
    public static boolean await(Duration timeout, Supplier<Boolean> condition) {
        WorkflowContext ctx = WorkflowContextHolder.require();
        HistoryCursor cursor = ctx.getHistoryCursor();
        int seq = cursor.nextSeq();

        // Already resolved in a prior turn.
        if (cursor.findCompletion(seq, CommandType.AWAIT,
                java.util.Set.of(EventType.AWAIT_FIRED)).isPresent()) {
            // The resolving turn already wrote AWAIT_FIRED. If timed out, return false.
            var fired = cursor.findBySeqAndType(seq, EventType.AWAIT_FIRED).get();
            return !payloadHasTimeout(fired.getPayload());
        }

        if (condition.get()) {
            if (cursor.isQueryMode()) return true;
            ctx.getEventSink().append(EventType.AWAIT_FIRED, CommandType.AWAIT, seq, null,
                    "{\"reason\":\"condition\"}");
            ctx.getWakeupRegistrar().deleteAwait(seq);
            return true;
        }
        if (cursor.isQueryMode()) {
            throw new QueryReplayBlockedException("workflow parked at await seq=" + seq);
        }

        if (cursor.findBySeqAndType(seq, EventType.AWAIT_BLOCKED).isEmpty()) {
            Instant deadline = timeout != null ? Instant.now().plus(timeout) : null;
            ctx.getEventSink().append(EventType.AWAIT_BLOCKED, CommandType.AWAIT, seq, null,
                    deadline != null ? "{\"deadline\":\"" + deadline + "\"}" : "{}");
            ctx.getWakeupRegistrar().registerAwait(seq, deadline);
        }
        throw new WorkflowParkedException(WorkflowParkedException.Kind.AWAIT, seq);
    }

    private static boolean payloadHasTimeout(String payload) {
        return payload != null && payload.contains("\"reason\":\"timeout\"");
    }

    // ── Signals ─────────────────────────────────────────────────────────────
    //
    // Signals have no blocking primitive here. An external caller invokes
    // SignalBus.send(workflowId, name, payload); the engine records a SIGNAL_RECEIVED
    // event and re-runs the workflow turn. A @SignalMethod handler on the workflow
    // mutates a field, and the entry method's await(timeout, () -> field...) observes
    // it and unblocks.
}
