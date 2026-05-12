package com.beeline.temporalmini.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.util.concurrent.Callable;

public class WorkflowMetrics {
    private final MeterRegistry registry;

    public WorkflowMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public <T> T record(String workflowName, Callable<T> workflow) throws Exception {
        Timer.Sample sample = Timer.start(registry);
        String status = "success";
        try {
            return workflow.call();
        } catch (Exception e) {
            status = "failure";
            throw e;
        } finally {
            sample.stop(timerFor(workflowName, status));
        }
    }

    private Timer timerFor(String workflowName, String status) {
        return Timer.builder("temporalmini.workflow.duration")
                .description("Workflow execution time (per attempt)")
                .tag("workflow", workflowName)
                .tag("status", status)
                .publishPercentileHistogram()
                .register(registry);
    }
}
