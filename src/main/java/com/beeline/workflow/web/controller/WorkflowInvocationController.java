package com.beeline.workflow.web.controller;

import com.beeline.workflow.engine.update.UpdateRegistry;
import com.beeline.workflow.web.dto.QueryRequestDto;
import com.beeline.workflow.web.dto.UpdateRequestDto;
import com.beeline.workflow.web.service.WorkflowInvocationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/workflow/api/workflows")
@CrossOrigin(origins = "*")
public class WorkflowInvocationController {

    private final WorkflowInvocationService service;

    public WorkflowInvocationController(WorkflowInvocationService service) {
        this.service = service;
    }

    @PostMapping("/{id}/query/{name}")
    public ResponseEntity<Object> query(@PathVariable Long id,
                                        @PathVariable String name,
                                        @RequestBody(required = false) QueryRequestDto body) {
        List<Object> args = body != null ? body.args() : List.of();
        Object result = service.query(id, name, args);
        return ResponseEntity.ok(result);
    }

    @PostMapping("/{id}/update/{name}")
    public ResponseEntity<UpdateRegistry.UpdateResult> update(@PathVariable Long id,
                                                              @PathVariable String name,
                                                              @RequestBody(required = false) UpdateRequestDto body,
                                                              @RequestParam(defaultValue = "30000") long timeoutMs) {
        List<Object> args = body != null ? body.args() : List.of();
        String updateId = service.dispatchUpdate(id, name, args);
        UpdateRegistry.UpdateResult result = service.awaitUpdate(updateId, timeoutMs);
        return ResponseEntity.ok(result);
    }
}
