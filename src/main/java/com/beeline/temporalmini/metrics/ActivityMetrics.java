package com.beeline.temporalmini.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.util.concurrent.Callable;

public class ActivityMetrics {
    private final MeterRegistry registry;

    public ActivityMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public <T> T record(String workflowName, String activityName, Callable<T> activity) throws Exception {
        Timer.Sample sample = Timer.start(registry);
        String status = "success";
        try {
            return activity.call();
        } catch (Exception e) {
            status = "failure";
            throw e;
        } finally {
            sample.stop(timerFor(workflowName, activityName, status));
        }
    }

    private Timer timerFor(String workflowName,String activityName, String status) {
        return Timer.builder("temporalmini.activity.duration")
                .description("Activity execution time")
                .tag("activity", activityName)
                .tag("workflow", workflowName)
                .tag("status", status)
                .publishPercentileHistogram()   // опционально: даёт p50/p95/p99 в Prometheus
                .register(registry);
    }




//    private final ConcurrentHashMap<String, Timer> timers = new ConcurrentHashMap<>();
//
//    private Timer timerFor(String activityName) {
//        return timers.computeIfAbsent(activityName, this::buildTimer);
//    }
//
//    private Timer buildTimer(String activityName) {
//        return Timer.builder("temporalmini.activity.duration")
//                .tag("activity", activityName)
//                .publishPercentileHistogram()
//                .register(registry);
//    }

}
