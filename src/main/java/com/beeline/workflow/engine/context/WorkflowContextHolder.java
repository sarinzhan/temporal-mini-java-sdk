package com.beeline.workflow.engine.context;

import com.beeline.workflow.engine.command.CommandContext;

/**
 * ThreadLocal carrier for the per-turn {@link CommandContext}. Set by the turn runner before it
 * invokes the workflow entry method and cleared in {@code finally}. The {@code Workflow} facade
 * methods (activity, sideEffect, getVersion) read this to find the dispatcher / replay state /
 * event log for the current turn.
 */
public final class WorkflowContextHolder {

    private static final ThreadLocal<CommandContext> CONTEXT = new ThreadLocal<>();

    private WorkflowContextHolder() {}

    public static CommandContext requireCommandContext() {
        CommandContext ctx = CONTEXT.get();
        if (ctx == null) {
            throw new IllegalStateException(
                    "No workflow context bound to the current thread. " +
                    "Workflow facade methods (activity, sideEffect, getVersion) may only be invoked from within a workflow execution.");
        }
        return ctx;
    }

    public static CommandContext current() {
        return CONTEXT.get();
    }

    public static void set(CommandContext ctx) {
        CONTEXT.set(ctx);
    }

    public static void clear() {
        CONTEXT.remove();
    }
}
