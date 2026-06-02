package com.beeline.workflow.it.support;

import com.beeline.workflow.core.annotation.WorkflowImpl;
import com.beeline.workflow.core.api.Workflow;
import com.beeline.workflow.core.config.ActivityOptions;
import com.beeline.workflow.core.config.RetryPolicy;

import java.time.Duration;

/**
 * One workflow that drives every integration scenario, selected by {@link Scenario#type()}.
 * Activity bodies delegate to the injected {@link TestActivities} so a test can assert exactly how
 * many times each one really executed.
 */
@WorkflowImpl
public class ScenarioWorkflowImpl implements ScenarioWorkflow {

    private final TestActivities activities;

    public ScenarioWorkflowImpl(TestActivities activities) {
        this.activities = activities;
    }

    @Override
    public String run(Scenario s) {
        ActivityOptions opts = optionsFor(s);
        String key = s.key();

        return switch (s.type()) {
            case "happy" -> Workflow.activity("reserve", opts, String.class,
                    () -> activities.reserve(key));

            case "flaky" -> Workflow.activity("flaky", opts, String.class,
                    () -> activities.flaky(key, s.failTimes()));

            case "alwaysFail" -> Workflow.activity("alwaysFail", opts, String.class,
                    () -> activities.alwaysFail(key));

            case "nonRetryable" -> Workflow.activity("nonRetryable", opts, String.class,
                    () -> activities.nonRetryable(key));

            case "timeout" -> Workflow.activity("slow", opts, String.class,
                    () -> activities.slowFirstTime(key, s.sleepMs()));

            case "throwInWorkflow" -> throw new IllegalStateException("workflow code blew up");

            case "replay" -> {
                // 'reserve' completes on the first turn; 'flaky' then fails and parks the turn.
                // Each retry replays the whole workflow — 'reserve' must be served from history,
                // never re-executed. The test asserts reserve ran exactly once.
                String reserved = Workflow.activity("reserve", opts, String.class,
                        () -> activities.reserve(key));
                String charged = Workflow.activity("flaky", opts, String.class,
                        () -> activities.flaky(key, s.failTimes()));
                yield reserved + "/" + charged;
            }

            case "idempotencyKey" -> Workflow.activity("flaky", opts, String.class,
                    () -> activities.flakyRecordingKey(key, s.failTimes()));

            case "version" -> {
                // getVersion followed by an activity: the trailing flaky activity parks and replays the
                // whole turn. getVersion must return the same version every replay AND must not shift the
                // activity's seq (the bug: it consumed a seq on the first turn but not on replay, so the
                // activity drifted to the marker's old seq and replay threw NonDeterminismException).
                int ver = Workflow.getVersion("order-flow", 1, 2);
                String charged = Workflow.activity("flaky", opts, String.class,
                        () -> activities.flaky(key, s.failTimes()));
                yield "v" + ver + "/" + charged;
            }

            case "sideEffect" -> {
                // sideEffect must be recorded once and replayed; the trailing flaky activity forces
                // replays. The test asserts the recorded value is stable and produced a single time.
                String se = Workflow.sideEffect(String.class, () -> activities.recordSideEffect(key));
                Workflow.activity("flaky", opts, String.class,
                        () -> activities.flaky(key, s.failTimes()));
                yield se;
            }

            default -> throw new IllegalArgumentException("unknown scenario type: " + s.type());
        };
    }

    private static ActivityOptions optionsFor(Scenario s) {
        RetryPolicy policy = RetryPolicy.newBuilder()
                .setMaxAttempts(s.maxAttempts())
                .setInitialInterval(Duration.ofMillis(50))
                .setBackoffCoefficient(1.0)          // constant, tiny backoff keeps the tests fast
                .setMaxInterval(Duration.ofMillis(50))
                .build();
        long timeoutMs = s.timeoutMs() > 0 ? s.timeoutMs() : 30_000;
        return ActivityOptions.newBuilder()
                .setStartToCloseTimeout(Duration.ofMillis(timeoutMs))
                .setRetryPolicy(policy)
                .build();
    }
}
