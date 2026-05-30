package com.beeline.workflow.engine.command;

public interface CommandHandler<C extends WorkflowCommand> {

    Class<C> commandClass();

    Object handle(C command, CommandContext ctx);
}
