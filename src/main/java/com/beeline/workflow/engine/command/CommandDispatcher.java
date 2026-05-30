package com.beeline.workflow.engine.command;

import com.beeline.workflow.engine.context.WorkflowContextHolder;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Routes commands built by the {@code Workflow} facade to their matching handlers.
 * Handler lookup is by exact runtime class — sealed {@link WorkflowCommand} keeps the set closed.
 */
public final class CommandDispatcher {

    private final Map<Class<? extends WorkflowCommand>, CommandHandler<? extends WorkflowCommand>> handlers;

    public CommandDispatcher(List<CommandHandler<? extends WorkflowCommand>> handlers) {
        Map<Class<? extends WorkflowCommand>, CommandHandler<? extends WorkflowCommand>> map = new HashMap<>();
        for (CommandHandler<? extends WorkflowCommand> h : handlers) {
            map.put(h.commandClass(), h);
        }
        this.handlers = map;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public Object dispatch(WorkflowCommand command) {
        CommandHandler handler = handlers.get(command.getClass());
        if (handler == null) {
            throw new IllegalStateException("No handler registered for command: " + command.getClass().getName());
        }
        CommandContext ctx = WorkflowContextHolder.requireCommandContext();
        return handler.handle(command, ctx);
    }
}
