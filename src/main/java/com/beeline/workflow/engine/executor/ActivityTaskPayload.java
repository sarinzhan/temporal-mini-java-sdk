package com.beeline.workflow.engine.executor;

import java.util.List;

/**
 * JSON payload of an {@code activity} {@link com.beeline.workflow.core.model.Task}. Carries
 * everything a worker needs to resolve and run the activity independently of the workflow thread
 * that scheduled it: the command seq it satisfies, how to find the bean+method, the serialized
 * arguments, the attempt number, and the resolved timeout/retry options.
 */
public record ActivityTaskPayload(
        int seq,
        String activityName,
        String activityInterface,
        String methodName,
        List<String> paramTypes,
        Object[] args,
        int attempt,
        long startToCloseTimeoutMillis,
        RetryPolicyPayload retry) {

    public record RetryPolicyPayload(
            int maxAttempts,
            long initialIntervalMillis,
            long maxIntervalMillis,
            double backoffCoefficient,
            List<String> noRetryOn) {
    }
}
