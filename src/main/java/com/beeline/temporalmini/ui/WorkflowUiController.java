package com.beeline.temporalmini.ui;

import com.beeline.temporalmini.*;
import org.springframework.data.domain.*;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/temporal-mini/api")
public class WorkflowUiController {

    private final WorkflowRepository workflowRepository;
    private final ActivityRepository activityRepository;

    public WorkflowUiController(WorkflowRepository workflowRepository,
                                ActivityRepository activityRepository) {
        this.workflowRepository = workflowRepository;
        this.activityRepository = activityRepository;
    }

    @GetMapping("/stats")
    public Map<String, Long> stats() {
        Map<String, Long> result = new LinkedHashMap<>();
        for (WorkflowState state : WorkflowState.values()) {
            result.put(state.name(), workflowRepository.countByState(state));
        }
        return result;
    }

    @GetMapping("/workflows")
    public Page<WorkflowEntity> workflows(
            @RequestParam(required = false) WorkflowState state,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by("id").descending());
        return state != null
                ? workflowRepository.findByState(state, pageable)
                : workflowRepository.findAll(pageable);
    }

    @GetMapping("/workflows/{id}/activities")
    public List<Activity> activities(@PathVariable Long id) {
        return activityRepository.findByWorkflowIdOrderByStartedAt(id);
    }
}
