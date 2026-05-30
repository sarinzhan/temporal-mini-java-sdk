package com.beeline.workflow.core.api;

import com.beeline.workflow.core.config.ActivityOptions;
import com.beeline.workflow.engine.command.ActivityCommand;
import com.beeline.workflow.engine.command.CommandContext;
import com.beeline.workflow.engine.command.SideEffectCommand;
import com.beeline.workflow.engine.command.VersionCommand;
import com.beeline.workflow.engine.context.WorkflowContextHolder;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.lang.reflect.Type;
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
    //
    // IMPORTANT: the return type is part of the on-disk replay contract. A Java lambda erases its
    // generic type, so the untyped Supplier/Function overloads below cannot recover the type and
    // the codec must fall back to the runtime class recorded in the payload — which breaks for
    // generic results (e.g. List<Order>), interface/abstract return types, or results whose class
    // is not on the classpath at replay time. PREFER the typed overloads that take an explicit
    // Class<T> or TypeReference<T>; those persist the exact type and replay deterministically.

    // -- untyped (best-effort; see note above) --

    public static <T> T activity(Supplier<T> fn) {
        return activity(null, ActivityOptions.defaultOptions(), (Type) null, fn);
    }

    public static <T> T activity(String name, Supplier<T> fn) {
        return activity(name, ActivityOptions.defaultOptions(), (Type) null, fn);
    }

    public static <T> T activity(ActivityOptions options, Supplier<T> fn) {
        return activity(null, options, (Type) null, fn);
    }

    public static <T> T activity(String name, ActivityOptions options, Supplier<T> fn) {
        return activity(name, options, (Type) null, fn);
    }

    // -- typed: Class<T> --

    public static <T> T activity(Class<T> returnType, Supplier<T> fn) {
        return activity(null, ActivityOptions.defaultOptions(), (Type) returnType, fn);
    }

    public static <T> T activity(String name, Class<T> returnType, Supplier<T> fn) {
        return activity(name, ActivityOptions.defaultOptions(), (Type) returnType, fn);
    }

    public static <T> T activity(String name, ActivityOptions options, Class<T> returnType, Supplier<T> fn) {
        return activity(name, options, (Type) returnType, fn);
    }

    // -- typed: TypeReference<T> (for generics like List<Order>) --

    public static <T> T activity(String name, ActivityOptions options, TypeReference<T> returnType, Supplier<T> fn) {
        return activity(name, options, returnType.getType(), fn);
    }

    public static <T> T activity(TypeReference<T> returnType, Supplier<T> fn) {
        return activity(null, ActivityOptions.defaultOptions(), returnType.getType(), fn);
    }

    // -- canonical typed entry point --

    @SuppressWarnings("unchecked")
    public static <T> T activity(String name, ActivityOptions options, Type returnType, Supplier<T> fn) {
        return (T) dispatch(new ActivityCommand(name, options, returnType, () -> fn.get()));
    }

    // -- void --

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
        dispatch(new ActivityCommand(name, options, void.class, () -> { fn.run(); return null; }));
    }

    // -- Function<I,O> --

    public static <I, O> O activity(I input, Function<I, O> fn) {
        return activity(null, ActivityOptions.defaultOptions(), (Type) null, input, fn);
    }

    public static <I, O> O activity(String name, Class<O> returnType, I input, Function<I, O> fn) {
        return activity(name, ActivityOptions.defaultOptions(), (Type) returnType, input, fn);
    }

    public static <I, O> O activity(String name, ActivityOptions options, Class<O> returnType, I input, Function<I, O> fn) {
        return activity(name, options, (Type) returnType, input, fn);
    }

    @SuppressWarnings("unchecked")
    public static <I, O> O activity(String name, ActivityOptions options, Type returnType, I input, Function<I, O> fn) {
        return (O) dispatch(new ActivityCommand(name, options, returnType, () -> fn.apply(input)));
    }

    public static <I> void activity(I input, Consumer<I> fn) {
        activity(null, ActivityOptions.defaultOptions(), input, fn);
    }

    public static <I> void activity(String name, ActivityOptions options, I input, Consumer<I> fn) {
        dispatch(new ActivityCommand(name, options, void.class, () -> { fn.accept(input); return null; }));
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
