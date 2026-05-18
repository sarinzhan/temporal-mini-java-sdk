package com.beeline.workflow.web.controller;

import com.beeline.workflow.web.dto.ActivityOverrideDto;
import com.beeline.workflow.web.service.ActivityOptionsOverrideService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/workflow/api/activity-overrides")
@CrossOrigin(origins = "*")
public class ActivityOverridesController {

    private final ActivityOptionsOverrideService service;

    public ActivityOverridesController(ActivityOptionsOverrideService service) {
        this.service = service;
    }

    @GetMapping
    public List<ActivityOverrideDto> list() {
        return service.list();
    }

    @GetMapping("/{activityName}")
    public ResponseEntity<ActivityOverrideDto> one(@PathVariable String activityName) {
        return service.get(activityName)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/{activityName}")
    public ActivityOverrideDto save(@PathVariable String activityName,
                                    @RequestBody ActivityOverrideDto body) {
        ActivityOverrideDto withName = new ActivityOverrideDto(
                activityName,
                body.startToCloseMs(),
                body.maxAttempts(),
                body.initialIntervalMs(),
                body.backoffCoefficient(),
                body.maxIntervalMs());
        return service.save(withName);
    }

    @DeleteMapping("/{activityName}")
    public ResponseEntity<Void> delete(@PathVariable String activityName) {
        service.delete(activityName);
        return ResponseEntity.noContent().build();
    }
}
