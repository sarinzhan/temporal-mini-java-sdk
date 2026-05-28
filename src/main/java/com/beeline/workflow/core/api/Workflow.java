package com.beeline.workflow.core.api;

import com.beeline.workflow.core.config.ActivityOptions;
import com.beeline.workflow.core.model.EventType;
import com.beeline.workflow.engine.context.WorkflowContextHolder;
import com.beeline.workflow.engine.replay.CommandType;
import com.beeline.workflow.engine.replay.HistoryCursor;
import tools.jackson.databind.ObjectMapper;

import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Facade used from inside workflow code. Activities are passed as inline lambdas and run on the
 * workflow thread; their results are recorded to history and replayed on re-run. Identity of an
 * activity is its command seq (call order) — an optional name is recorded for readability only.
 */
public final class Workflow {

    private Workflow() {}

    /** Returned by getVersion for workflows that pre-date the change. */
    public static final int DEFAULT_VERSION = -1;

    private static volatile ObjectMapper objectMapper;

    public static void installObjectMapper(ObjectMapper mapper) {
        Workflow.objectMapper = mapper;
    }

    // ── Activities: inline lambdas ───────────────────────────────────────────
    //
    // Supplier<T>  — value, no input.            Runnable      — void, no input.
    // Function<I,O>— value, with input.          Consumer<I>   — void, with input.
    //
    // NOTE on overloads: Java cannot always disambiguate a value-returning lambda passed where both a
    // value (Supplier/Function) and a void (Runnable/Consumer) overload apply. The no-input pair
    // (Supplier/Runnable) resolves cleanly; for the with-input pair (Function/Consumer) a
    // value-returning method-call lambda may be ambiguous — cast the lambda
    // (e.g. {@code (Function<I,O>) x -> ...}) or capture the input in a Supplier/Runnable instead.

    public static <T> T activity(Supplier<T> fn) {
        return activity(null, ActivityOptions.defaultOptions(), fn);
    }

    public static <T> T activity(String name, Supplier<T> fn) {
        return activity(name, ActivityOptions.defaultOptions(), fn);
    }

    public static <T> T activity(ActivityOptions options, Supplier<T> fn) {
        return activity(null, options, fn);
    }

    @SuppressWarnings("unchecked")
    public static <T> T activity(String name, ActivityOptions options, Supplier<T> fn) {
        return (T) executor().execute(name, options, null, () -> fn.get());
    }

    public static void activity(Runnable fn) {
        activity(null, ActivityOptions.defaultOptions(), fn);
    }

    public static void activity(String name, Runnable fn) {
        activity(name, ActivityOptions.defaultOptions(), fn);
    }

    public static void activity(ActivityOptions options, Runnable fn) {
        activity(null, options, fn);
    }

    public static void activity(String name, ActivityOptions options, Runnable fn) {
        executor().execute(name, options, null, () -> { fn.run(); return null; });
    }

    public static <I, O> O activity(I input, Function<I, O> fn) {
        return activity(null, ActivityOptions.defaultOptions(), input, fn);
    }

    @SuppressWarnings("unchecked")
    public static <I, O> O activity(String name, ActivityOptions options, I input, Function<I, O> fn) {
        return (O) executor().execute(name, options, null, () -> fn.apply(input));
    }

    public static <I> void activity(I input, Consumer<I> fn) {
        activity(null, ActivityOptions.defaultOptions(), input, fn);
    }

    public static <I> void activity(String name, ActivityOptions options, I input, Consumer<I> fn) {
        executor().execute(name, options, null, () -> { fn.accept(input); return null; });
    }

    private static com.beeline.workflow.engine.executor.ActivityExecutor executor() {
        return WorkflowContextHolder.require().getActivityExecutor();
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
}
