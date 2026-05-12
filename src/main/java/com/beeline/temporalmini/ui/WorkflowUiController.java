package com.beeline.temporalmini.ui;

import com.beeline.temporalmini.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.*;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.concurrent.ThreadPoolExecutor;

// (sort whitelist lives below — keeps the import block tidy)

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
     * Allow-list of fields the client may sort on. Anything else falls back to {@code id}.
     * The list is intentionally narrow — the table only exposes these columns as sortable.
     */
    private static final Set<String> SORTABLE_FIELDS = Set.of("id", "createdAt", "startedAt", "state");

    /**
     * Paged workflow list. Filtering supports multiple states via repeated query
     * params: {@code ?state=NEW&state=RUNNABLE} or comma-separated {@code ?state=NEW,RUNNABLE}.
     * Sorting via {@code ?sort=field,dir} (e.g. {@code sort=createdAt,desc}); default is
     * {@code id,desc}.
     */
    @GetMapping("/workflows")
    public Page<WorkflowEntity> workflows(
            @RequestParam(name = "state", required = false) List<WorkflowState> states,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "id,desc") String sort) {
        Pageable pageable = PageRequest.of(page, size, parseSort(sort));
        if (states == null || states.isEmpty()) {
            return workflowRepository.findAll(pageable);
        }
        if (states.size() == 1) {
            return workflowRepository.findByState(states.get(0), pageable);
        }
        return workflowRepository.findByStateIn(states, pageable);
    }

    /** Parses {@code field,dir} → {@link Sort}; falls back to {@code id,desc} on bad input. */
    private static Sort parseSort(String raw) {
        if (raw == null || raw.isBlank()) return Sort.by(Sort.Direction.DESC, "id");
        String[] parts = raw.split(",", 2);
        String field = SORTABLE_FIELDS.contains(parts[0]) ? parts[0] : "id";
        Sort.Direction dir = (parts.length > 1 && "asc".equalsIgnoreCase(parts[1].trim()))
                ? Sort.Direction.ASC : Sort.Direction.DESC;
        return Sort.by(dir, field);
    }

    @GetMapping("/workflows/{id}")
    public WorkflowEntity workflow(@PathVariable Long id) {
        return workflowRepository.findById(id).orElseThrow();
    }

    @GetMapping("/workflows/{id}/activities")
    public List<Activity> activities(@PathVariable Long id) {
        return activityRepository.findByWorkflowIdOrderByStartedAt(id);
    }

    /**
     * Returns {workflowId: {name, attempt, lastAttemptAt}} for the latest activity of
     * each given workflow. {@code attempt} is the attempt number of the latest row,
     * which equals the total attempts the engine has made for that activity name
     * (every retry inserts a new row with attempt = previous + 1).
     */
    @GetMapping("/last-activities")
    public Map<Long, Map<String, Object>> lastActivities(@RequestParam("ids") List<Long> ids) {
        if (ids == null || ids.isEmpty()) return Map.of();
        Map<Long, Map<String, Object>> result = new LinkedHashMap<>();
        for (Activity a : activityRepository.findLatestActivities(ids)) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("name", a.getName());
            data.put("attempt", a.getAttempt());
            data.put("lastAttemptAt", a.getStartedAt());
            result.put(a.getWorkflowId(), data);
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

    @PostMapping("/workflows/{id}/stop")
    public ResponseEntity<?> stop(@PathVariable Long id) {
        try {
            workflowEngine.stop(id);
            return ResponseEntity.ok(Map.of("status", "ok"));
        } catch (IllegalStateException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        }
    }

    @PostMapping("/workflows/{id}/resume")
    public ResponseEntity<?> resume(@PathVariable Long id) {
        try {
            workflowEngine.resume(id);
            return ResponseEntity.ok(Map.of("status", "ok"));
        } catch (IllegalStateException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        }
    }

    @PostMapping("/workflows/{id}/restart")
    public ResponseEntity<?> restart(@PathVariable Long id) {
        try {
            workflowEngine.restart(id);
            return ResponseEntity.ok(Map.of("status", "ok"));
        } catch (IllegalStateException | IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        }
    }

    public record RestartFromActivityRequest(Long activityId) {}

    @PostMapping("/workflows/{id}/restart-from-activity")
    public ResponseEntity<?> restartFromActivity(@PathVariable Long id,
                                                 @org.springframework.web.bind.annotation.RequestBody RestartFromActivityRequest body) {
        try {
            workflowEngine.restartFromActivity(id, body.activityId());
            return ResponseEntity.ok(Map.of("status", "ok"));
        } catch (IllegalStateException | IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        }
    }

    /**
     * Bulk action body. Either {@code ids} OR a {@code from/to} window is required;
     * {@code states} is an optional filter applied to the time window.
     */
    public record BulkRequest(List<Long> ids,
                              java.time.LocalDateTime from,
                              java.time.LocalDateTime to,
                              List<WorkflowState> states) {}

    public record BulkResponse(int affected) {}

    @PostMapping("/workflows/bulk/stop")
    public BulkResponse bulkStop(@org.springframework.web.bind.annotation.RequestBody BulkRequest body) {
        return new BulkResponse(workflowEngine.stopAll(resolveIds(body)));
    }

    @PostMapping("/workflows/bulk/resume")
    public BulkResponse bulkResume(@org.springframework.web.bind.annotation.RequestBody BulkRequest body) {
        return new BulkResponse(workflowEngine.resumeAll(resolveIds(body)));
    }

    @PostMapping("/workflows/bulk/restart")
    public BulkResponse bulkRestart(@org.springframework.web.bind.annotation.RequestBody BulkRequest body) {
        return new BulkResponse(workflowEngine.restartAll(resolveIds(body)));
    }

    @PostMapping("/workflows/bulk/run-now")
    public BulkResponse bulkRunNow(@org.springframework.web.bind.annotation.RequestBody BulkRequest body) {
        return new BulkResponse(workflowEngine.runNowAll(resolveIds(body)));
    }

    public record PayloadRequest(String payload) {}

    /** Update the workflow's input payload (allowed in NEW/STOPPED/FAILED). */
    @PutMapping("/workflows/{id}/payload")
    public ResponseEntity<?> setPayload(@PathVariable Long id,
                                        @RequestBody PayloadRequest body) {
        try {
            workflowEngine.setPayload(id, body.payload());
            return ResponseEntity.ok(Map.of("status", "ok"));
        } catch (IllegalStateException | IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        }
    }

    @PutMapping("/workflows/{id}/activities/{activityId}/input")
    public ResponseEntity<?> setActivityInput(@PathVariable Long id, @PathVariable Long activityId,
                                              @RequestBody PayloadRequest body) {
        try {
            workflowEngine.setActivityPayload(id, activityId, body.payload(), false);
            return ResponseEntity.ok(Map.of("status", "ok"));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        }
    }

    @PutMapping("/workflows/{id}/activities/{activityId}/output")
    public ResponseEntity<?> setActivityOutput(@PathVariable Long id, @PathVariable Long activityId,
                                               @RequestBody PayloadRequest body) {
        try {
            workflowEngine.setActivityPayload(id, activityId, body.payload(), true);
            return ResponseEntity.ok(Map.of("status", "ok"));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        }
    }

    /** Either explicit ids or a creation-time window with optional state filter. */
    private List<Long> resolveIds(BulkRequest body) {
        if (body.ids() != null && !body.ids().isEmpty()) return body.ids();
        if (body.from() != null && body.to() != null) {
            return workflowRepository.findIdsByCreatedAtRange(body.from(), body.to(), body.states());
        }
        return List.of();
    }
}
