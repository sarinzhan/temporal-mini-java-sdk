package com.beeline.workflow.it.support;

import com.beeline.workflow.core.api.Workflow;
import com.beeline.workflow.core.exception.NonRetryableException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Controllable side-effecting bean used by the integration scenarios. Every method records how many
 * times it actually ran (keyed by the scenario's unique key), so a test can assert the real number
 * of invocations — which is the whole point when verifying retries and replay caching.
 *
 * <p>State is keyed per scenario, so the Spring context (and therefore this singleton) can be safely
 * reused across test classes without cross-talk, as long as each test uses a unique key.
 */
@Service
public class TestActivities {

    private final Map<String, AtomicInteger> invocations = new ConcurrentHashMap<>();
    private final Map<String, List<String>> sideEffectValues = new ConcurrentHashMap<>();
    private final Map<String, List<String>> observedKeys = new ConcurrentHashMap<>();

    /** How many times the named activity actually executed for this scenario key. */
    public int invocationCount(String key, String activity) {
        AtomicInteger c = invocations.get(counterKey(key, activity));
        return c == null ? 0 : c.get();
    }

    /** Values produced by {@link #recordSideEffect} for this scenario key (one per real execution). */
    public List<String> sideEffectValues(String key) {
        return sideEffectValues.getOrDefault(key, List.of());
    }

    // ── activity bodies ───────────────────────────────────────────────────────

    /** Always succeeds; returns a deterministic value. */
    public String reserve(String key) {
        record(key, "reserve");
        return "RES-" + key;
    }

    /** Fails the first {@code failTimes} executions, then succeeds. */
    public String flaky(String key, int failTimes) {
        int attempt = record(key, "flaky");
        if (attempt <= failTimes) {
            throw new RuntimeException("flaky boom on attempt " + attempt);
        }
        return "OK-" + key + "-" + attempt;
    }

    /**
     * Like {@link #flaky} but first records {@link Workflow#currentActivityKey()} on EVERY execution
     * (including the failing attempts). Lets a test prove the idempotency key is visible inside the
     * body and identical across retries/replays.
     */
    public String flakyRecordingKey(String key, int failTimes) {
        observedKeys.computeIfAbsent(key, k -> Collections.synchronizedList(new ArrayList<>()))
                .add(Workflow.currentActivityKey());
        return flaky(key, failTimes);
    }

    /** Idempotency keys observed by {@link #flakyRecordingKey} for this scenario key (one per real run). */
    public List<String> observedKeys(String key) {
        return observedKeys.getOrDefault(key, List.of());
    }

    /** Always throws a retryable exception. */
    public String alwaysFail(String key) {
        record(key, "alwaysFail");
        throw new RuntimeException("permanent boom");
    }

    /** Throws a {@link NonRetryableException} — the retry decider must stop immediately. */
    public String nonRetryable(String key) {
        record(key, "nonRetryable");
        throw new NonRetryableException("do not retry me");
    }

    /** Sleeps {@code sleepMs} only on its first execution (to trip a timeout), then succeeds. */
    public String slowFirstTime(String key, long sleepMs) {
        int attempt = record(key, "slow");
        if (attempt == 1) {
            try {
                Thread.sleep(sleepMs);
            } catch (InterruptedException e) {
                // Timed out and cancelled by the engine. Thread.sleep already cleared the interrupt
                // flag, so the pooled thread is clean for reuse. Surface as a normal failure.
                throw new RuntimeException("interrupted while sleeping (timed out)", e);
            }
        }
        return "SLOW-" + attempt;
    }

    /** Records a fresh random value each time it actually runs. Used to prove sideEffect caching. */
    public String recordSideEffect(String key) {
        String value = "SE-" + UUID.randomUUID();
        sideEffectValues.computeIfAbsent(key, k -> Collections.synchronizedList(new ArrayList<>())).add(value);
        return value;
    }

    private int record(String key, String activity) {
        return invocations.computeIfAbsent(counterKey(key, activity), k -> new AtomicInteger()).incrementAndGet();
    }

    private static String counterKey(String key, String activity) {
        return key + "::" + activity;
    }
}
