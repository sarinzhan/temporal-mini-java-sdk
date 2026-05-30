package com.beeline.workflow.engine.command;

import java.util.function.Supplier;

public record SideEffectCommand(
        Class<?> resultType,
        Supplier<Object> body
) implements WorkflowCommand {}
