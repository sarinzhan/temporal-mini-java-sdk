package com.beeline.workflow.web.controller;

import com.beeline.workflow.core.model.Event;
import com.beeline.workflow.core.model.WorkflowInstance;
import com.beeline.workflow.core.model.WorkflowStatus;
import com.beeline.workflow.persistence.repository.EventRepository;
import com.beeline.workflow.persistence.repository.WorkflowRepository;
import com.beeline.workflow.spring.api.WorkflowClient;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/**
 * Minimal REST surface: start a workflow by type, read its status/result, and read its event
 * history (the source of truth for replay).
 */
@RestController
@RequestMapping("/workflow/api/workflows")
public class WorkflowController {

    private final WorkflowClient client;
    private final WorkflowRepository workflowRepository;
    private final EventRepository eventRepository;

    public WorkflowController(WorkflowClient client,
                              WorkflowRepository workflowRepository,
                              EventRepository eventRepository) {
        this.client = client;
        this.workflowRepository = workflowRepository;
        this.eventRepository = eventRepository;
    }

    /** Start a workflow by its registered type. Request body is the workflow input (raw JSON). */
    @PostMapping("/{type}")
    public StartResponse start(@PathVariable String type, @RequestBody(required = false) String inputJson) {
        Long id = client.startByType(type, inputJson);
        return new StartResponse(id, type);
    }

    /**
     * List recent workflows (newest first), capped by {@code limit}. The UI paginates and filters
     * client-side over this set; raise the cap or add server-side paging if histories grow large.
     */
    @GetMapping
    public List<WorkflowView> list(@RequestParam(defaultValue = "500") int limit) {
        int capped = Math.min(Math.max(limit, 1), 2000);
        return workflowRepository.findAll(
                        PageRequest.of(0, capped, Sort.by(Sort.Direction.DESC, "createdAt")))
                .map(WorkflowView::of)
                .toList();
    }

    @GetMapping("/{id}")
    public ResponseEntity<WorkflowView> get(@PathVariable Long id) {
        return workflowRepository.findById(id)
                .map(wf -> ResponseEntity.ok(WorkflowView.of(wf)))
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/{id}/events")
    public List<EventView> events(@PathVariable Long id) {
        return eventRepository.findByWorkflowIdOrderByIdAsc(id).stream()
                .map(EventView::of)
                .toList();
    }

    public record StartResponse(Long workflowId, String type) {}

    /** Full workflow view used by both the list and the detail screens. */
    public record WorkflowView(Long id, String workflowType, WorkflowStatus status,
                               String input, String result, String error, String taskQueue,
                               Instant createdAt, Instant updatedAt, Instant completedAt) {
        static WorkflowView of(WorkflowInstance w) {
            // taskQueue is null: the engine has no per-workflow queue concept (single task queue).
            return new WorkflowView(w.getId(), w.getWorkflowType(), w.getStatus(),
                    w.getInput(), w.getResult(), w.getError(), null,
                    w.getCreatedAt(), w.getUpdatedAt(), w.getCompletedAt());
        }
    }

    public record EventView(Long id, Long workflowId, String eventType, String commandType,
                            Integer seq, String activityName, String payload, Instant createdAt) {
        static EventView of(Event e) {
            return new EventView(e.getId(), e.getWorkflowId(), e.getEventType().name(),
                    e.getCommandType(), e.getSeq(), e.getActivityName(), e.getPayload(),
                    e.getCreatedAt());
        }
    }
}
