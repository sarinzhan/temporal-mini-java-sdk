package com.beeline.workflow.core.api;

import com.beeline.workflow.core.config.ActivityOptions;
import com.beeline.workflow.engine.command.ActivityCommand;
import com.beeline.workflow.engine.command.CommandContext;
import com.beeline.workflow.engine.command.SideEffectCommand;
import com.beeline.workflow.engine.command.VersionCommand;
import com.beeline.workflow.engine.context.WorkflowContextHolder;
import tools.jackson.databind.ObjectMapper;

import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Facade used from inside workflow code. Internally all calls build a
 * {@link com.beeline.workflow.engine.command.WorkflowCommand} and route it through
 * {@link com.beeline.workflow.engine.command.CommandDispatcher}. The cursor / event log / retry
 * policy / payload codec live behind the handler — none of that is reachable from user code.
 */
public final class Workflow {

    private Workflow() {}

    /** Returned by getVersion for workflows that pre-date the change. */
    public static final int DEFAULT_VERSION = -1;

    /** Kept for backwards-compatibility with bootstrapping code; the codec uses its own mapper now. */
    public static void installObjectMapper(ObjectMapper mapper) {
        // No-op. PayloadCodec is wired through Spring with the same ObjectMapper.
    }

    // ── Activities: inline lambdas ───────────────────────────────────────────

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
        return (T) dispatch(new ActivityCommand(name, options, null, () -> fn.get()));
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
        dispatch(new ActivityCommand(name, options, null, () -> { fn.run(); return null; }));
    }

    public static <I, O> O activity(I input, Function<I, O> fn) {
        return activity(null, ActivityOptions.defaultOptions(), input, fn);
    }

    @SuppressWarnings("unchecked")
    public static <I, O> O activity(String name, ActivityOptions options, I input, Function<I, O> fn) {
        return (O) dispatch(new ActivityCommand(name, options, null, () -> fn.apply(input)));
    }

    public static <I> void activity(I input, Consumer<I> fn) {
        activity(null, ActivityOptions.defaultOptions(), input, fn);
    }

    public static <I> void activity(String name, ActivityOptions options, I input, Consumer<I> fn) {
        dispatch(new ActivityCommand(name, options, null, () -> { fn.accept(input); return null; }));
    }

    // ── Determinism helpers ─────────────────────────────────────────────────

    /**
     * Execute {@code fn} exactly once and persist its result; on replay returns the persisted value
     * without re-running {@code fn}. Use for non-deterministic reads (random IDs, current time, etc).
     */
    @SuppressWarnings("unchecked")
    public static <T> T sideEffect(Class<T> type, Supplier<T> fn) {
        return (T) dispatch(new SideEffectCommand(type, () -> fn.get()));
    }

    /**
     * Returns a version for a code-change point. On the first run for this changeId in a
     * workflow, writes a VERSION_MARKER with version={@code maxSupported} and returns it.
     * On subsequent replays returns the recorded version. Workflows that pre-date the
     * change return {@link #DEFAULT_VERSION}, allowing the old code path to be taken.
     */
    public static int getVersion(String changeId, int minSupported, int maxSupported) {
        return (int) dispatch(new VersionCommand(changeId, minSupported, maxSupported));
    }

    private static Object dispatch(com.beeline.workflow.engine.command.WorkflowCommand cmd) {
        CommandContext ctx = WorkflowContextHolder.requireCommandContext();
        return ctx.dispatcher().dispatch(cmd);
    }
}
