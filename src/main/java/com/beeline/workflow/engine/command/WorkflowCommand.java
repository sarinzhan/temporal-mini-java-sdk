package com.beeline.workflow.engine.command;

/**
 * Marker for everything a workflow can ask the engine to do during a decision turn:
 * call an activity, record a sideEffect, get a version marker. Each command type has a
 * matching {@link CommandHandler}.
 */
public sealed interface WorkflowCommand
        permits ActivityCommand, SideEffectCommand, VersionCommand {
}
