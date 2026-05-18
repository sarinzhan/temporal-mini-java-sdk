package com.beeline.workflow.engine.executor;

import com.beeline.workflow.core.config.ActivityOptions;

import java.lang.reflect.Type;
import java.util.function.Supplier;

public interface ActivityExecutor {

    Object execute(String activityName,
                   ActivityOptions options,
                   Type returnType,
                   Supplier<Object> invocation);
}
