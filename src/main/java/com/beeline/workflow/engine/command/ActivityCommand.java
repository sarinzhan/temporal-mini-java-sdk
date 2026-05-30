package com.beeline.workflow.engine.command;

import com.beeline.workflow.core.config.ActivityOptions;

import java.lang.reflect.Type;
import java.util.function.Supplier;

public record ActivityCommand(
        String name,
        ActivityOptions options,
        Type returnType,
        Supplier<Object> body
) implements WorkflowCommand {}
