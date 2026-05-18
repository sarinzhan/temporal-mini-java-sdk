package com.beeline.workflow.web.controller;

import com.beeline.workflow.web.dto.AdminActionResponse;
import com.beeline.workflow.web.dto.SignalRequest;
import com.beeline.workflow.web.service.WorkflowAdminService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/workflow/api/workflows")
@CrossOrigin(origins = "*")
public class WorkflowAdminController {

    private final WorkflowAdminService admin;

    public WorkflowAdminController(WorkflowAdminService admin) {
        this.admin = admin;
    }

    @PostMapping("/{id}/cancel")
    public AdminActionResponse cancel(@PathVariable UUID id) {
        admin.cancel(id);
        return AdminActionResponse.success();
    }

    @PostMapping("/{id}/resume")
    public AdminActionResponse resume(@PathVariable UUID id) {
        admin.resume(id);
        return AdminActionResponse.success();
    }

    @PostMapping("/{id}/signal")
    public AdminActionResponse signal(@PathVariable UUID id, @RequestBody SignalRequest body) {
        admin.sendSignal(id, body.signalName(), body.payload());
        return AdminActionResponse.success();
    }

    @PostMapping("/{id}/activities/{activityName}/retry")
    public AdminActionResponse retry(@PathVariable UUID id, @PathVariable String activityName) {
        admin.retryDeadActivity(id, activityName);
        return AdminActionResponse.success();
    }
}
