package com.beeline.workflow.engine.executor;

import com.beeline.workflow.core.config.ActivityOptions;

import java.lang.reflect.Type;
import java.util.function.Supplier;

/**
 * Workflow-side entry point for activity invocation. The activity body runs <b>inline</b> on the
 * workflow thread. Using the workflow's {@code HistoryCursor}, the executor:
 * <ol>
 *   <li>takes the next seq;</li>
 *   <li>on replay, returns the cached {@code ACTIVITY_COMPLETED} result (without running the body)
 *       or re-throws a recorded terminal {@code ACTIVITY_FAILED};</li>
 *   <li>otherwise runs {@code body} (with the start-to-close timeout): success records
 *       {@code ACTIVITY_COMPLETED}; a retryable failure records {@code ACTIVITY_RETRY_SCHEDULED} +
 *       a {@code wflow.schedule} row and <b>parks</b> the turn (so the worker thread is freed during
 *       backoff); a terminal failure records {@code ACTIVITY_FAILED} and throws.</li>
 * </ol>
 */
public interface ActivityExecutor {

    /**
     * @param name        optional activity name (for history/readability); identity is the seq
     * @param options     timeout + retry policy
     * @param returnType  declared return type for deserializing the cached result on replay; may be
     *                    {@code null}, in which case the runtime type recorded with the result is used
     * @param body        the activity work; not run on replay
     */
    Object execute(String name, ActivityOptions options, Type returnType, Supplier<Object> body);
}
