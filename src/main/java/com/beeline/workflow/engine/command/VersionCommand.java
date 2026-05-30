package com.beeline.workflow.engine.command;

public record VersionCommand(
        String changeId,
        int minSupported,
        int maxSupported
) implements WorkflowCommand {}
