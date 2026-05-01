package com.beeline.temporalmini.ui;

import com.beeline.temporalmini.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.*;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.concurrent.ThreadPoolExecutor;

@RestController
@RequestMapping("/temporal-mini/api")
public class WorkflowUiController {

    private final WorkflowEngine workflowEngine;
    private final WorkflowRepository workflowRepository;
    private final ActivityRepository activityRepository;
    private final WorkflowRuntimeRegistry runtimeRegistry;
    private final ThreadPoolTaskExecutor workflowExecutor;

    public WorkflowUiController(WorkflowEngine workflowEngine,
                                WorkflowRepository workflowRepository,
                                ActivityRepository activityRepository,
                                WorkflowRuntimeRegistry runtimeRegistry,
                                @Qualifier(TemporalMiniAutoConfiguration.EXECUTOR_BEAN)
                                ThreadPoolTaskExecutor workflowExecutor) {
        this.workflowEngine = workflowEngine;
        this.workflowRepository = workflowRepository;
        this.activityRepository = activityRepository;
        this.runtimeRegistry = runtimeRegistry;
        this.workflowExecutor = workflowExecutor;
    }

    /**
     * Live snapshot of the workflow executor pool — busy vs. idle workers, queue depth.
     * Cheap, just reads counters off the underlying {@link ThreadPoolExecutor}.
     */
    @GetMapping("/pool")
    public PoolStats pool() {
        ThreadPoolExecutor tp = workflowExecutor.getThreadPoolExecutor();
        int active = tp.getActiveCount();
        int poolSize = tp.getPoolSize();
        return new PoolStats(
                active,
                Math.max(0, poolSize - active),
                poolSize,
                tp.getCorePoolSize(),
                tp.getMaximumPoolSize(),
                tp.getQueue().size(),
                tp.getQueue().size() + tp.getQueue().remainingCapacity()
        );
    }

    @GetMapping("/stats")
    public Map<String, Long> stats() {
        Map<String, Long> result = new LinkedHashMap<>();
        for (WorkflowState state : WorkflowState.values()) {
            result.put(state.name(), workflowRepository.countByState(state));
        }
        // RUNNING is a transient runtime view, not a DB state — surface it here so
        // the UI can show it as a separate stat card without an extra request.
        result.put("RUNNING", (long) runtimeRegistry.ids().size());
        return result;
    }

    /** Map of {workflowId: epochMillisStartedRunning} for currently-executing workflows. */
    @GetMapping("/runtime")
    public Map<Long, Long> runtime() {
        return runtimeRegistry.snapshot();
    }

    /**
     * Paged workflow list. Filtering supports multiple states via repeated query
     * params: {@code ?state=NEW&state=RUNNABLE} or comma-separated {@code ?state=NEW,RUNNABLE}.
     */
    @GetMapping("/workflows")
    public Page<WorkflowEntity> workflows(
            @RequestParam(name = "state", required = false) List<WorkflowState> states,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by("id").descending());
        if (states == null || states.isEmpty()) {
            return workflowRepository.findAll(pageable);
        }
        if (states.size() == 1) {
            return workflowRepository.findByState(states.get(0), pageable);
        }
        return workflowRepository.findByStateIn(states, pageable);
    }

    @GetMapping("/workflows/{id}")
    public WorkflowEntity workflow(@PathVariable Long id) {
        return workflowRepository.findById(id).orElseThrow();
    }

    @GetMapping("/workflows/{id}/activities")
    public List<Activity> activities(@PathVariable Long id) {
        return activityRepository.findByWorkflowIdOrderByStartedAt(id);
    }

    /** Returns {workflowId: {name, attempt}} for the latest activity of each given workflow. */
    @GetMapping("/last-activities")
    public Map<Long, Map<String, Object>> lastActivities(@RequestParam("ids") List<Long> ids) {
        if (ids == null || ids.isEmpty()) return Map.of();
        Map<Long, Map<String, Object>> result = new LinkedHashMap<>();
        for (Activity a : activityRepository.findLatestActivities(ids)) {
            result.put(a.getWorkflowId(), Map.of("name", a.getName(), "attempt", a.getAttempt()));
        }
        return result;
    }

    @PostMapping("/workflows/{id}/run-now")
    public ResponseEntity<?> runNow(@PathVariable Long id) {
        try {
            workflowEngine.runNow(id);
            return ResponseEntity.ok(Map.of("status", "ok"));
        } catch (IllegalStateException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        }
    }

    @PostMapping("/workflows/{id}/block")
    public ResponseEntity<?> block(@PathVariable Long id) {
        try {
            workflowEngine.block(id);
            return ResponseEntity.ok(Map.of("status", "ok"));
        } catch (IllegalStateException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        }
    }

    @PostMapping("/workflows/{id}/unblock")
    public ResponseEntity<?> unblock(@PathVariable Long id) {
        try {
            workflowEngine.unblock(id);
            return ResponseEntity.ok(Map.of("status", "ok"));
        } catch (IllegalStateException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        }
    }
}
