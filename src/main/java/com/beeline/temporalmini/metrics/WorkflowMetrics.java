package com.beeline.temporalmini.metrics;

import com.beeline.temporalmini.WorkflowRepository;
import com.beeline.temporalmini.WorkflowState;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.LocalDateTime;
import java.util.concurrent.Callable;

public class WorkflowMetrics {

    private final MeterRegistry registry;
    private final Counter newCounter;

    public WorkflowMetrics(MeterRegistry registry,
                           WorkflowRepository workflowRepository,
                           ThreadPoolTaskExecutor executor) {
        this.registry = registry;
        this.newCounter = Counter.builder("temporalmini.workflows.new")
                .description("Total workflows created")
                .register(registry);
        registerGauges(workflowRepository, executor);
    }

    public void recordCreated() {
        newCounter.increment();
    }

    private void registerGauges(WorkflowRepository repo, ThreadPoolTaskExecutor executor) {
        Gauge.builder("temporalmini.workflows.running", executor,
                        e -> e.getThreadPoolExecutor().getActiveCount())
                .description("Workflows currently executing in the thread pool")
                .register(registry);

        // время ожидания истекло — готовы к запуску (NEW + RETRY где nextRetryAt <= now)
        Gauge.builder("temporalmini.workflows.queued", repo, r -> r.countQueued(LocalDateTime.now()))
                .description("Workflows ready to be picked up by the scheduler")
                .register(registry);

        // ждут следующей попытки (RETRY где nextRetryAt > now)
        Gauge.builder("temporalmini.workflows.waiting", repo, r -> r.countWaiting(LocalDateTime.now()))
                .description("Workflows waiting for their next retry window")
                .register(registry);

        Gauge.builder("temporalmini.workflows.stopped", repo, r -> r.countByState(WorkflowState.STOPPED))
                .description("Workflows stopped manually")
                .register(registry);

        Gauge.builder("temporalmini.workflows.finished", repo, r -> r.countByState(WorkflowState.FINISHED))
                .description("Workflows completed successfully")
                .register(registry);

        Gauge.builder("temporalmini.workflows.failed", repo, r -> r.countByState(WorkflowState.FAILED))
                .description("Workflows failed permanently")
                .register(registry);
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
