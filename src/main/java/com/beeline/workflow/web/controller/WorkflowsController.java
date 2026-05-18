package com.beeline.workflow.web.controller;

import com.beeline.workflow.core.model.WorkflowStatus;
import com.beeline.workflow.web.dto.EventDto;
import com.beeline.workflow.web.dto.PageResponse;
import com.beeline.workflow.web.dto.PendingActivityDto;
import com.beeline.workflow.web.dto.WorkflowDetailDto;
import com.beeline.workflow.web.dto.WorkflowSummaryDto;
import com.beeline.workflow.web.service.WorkflowQueryService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/workflow/api/workflows")
@CrossOrigin(origins = "*")
public class WorkflowsController {

    private final WorkflowQueryService query;

    public WorkflowsController(WorkflowQueryService query) {
        this.query = query;
    }

    @GetMapping
    public PageResponse<WorkflowSummaryDto> list(
            @RequestParam(required = false) List<WorkflowStatus> status,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String id,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(required = false) String quick,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(required = false) String sort) {
        return query.search(status, type, id, from, to, quick, page, size, sort);
    }

    @GetMapping("/types")
    public List<String> types() {
        return query.workflowTypes();
    }

    @GetMapping("/{id}")
    public ResponseEntity<WorkflowDetailDto> one(@PathVariable UUID id) {
        return query.detail(id).map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/{id}/events")
    public List<EventDto> events(@PathVariable UUID id) {
        return query.events(id);
    }

    @GetMapping("/{id}/pending-activities")
    public List<PendingActivityDto> pending(@PathVariable UUID id) {
        return query.pendingActivities(id);
    }
}
