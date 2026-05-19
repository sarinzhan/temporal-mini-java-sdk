package com.beeline.workflow.web.service;

import com.beeline.workflow.core.model.ActivityResult;
import com.beeline.workflow.core.model.Event;
import com.beeline.workflow.core.model.RetryRecord;
import com.beeline.workflow.core.model.WorkflowInstance;
import com.beeline.workflow.core.model.WorkflowStatus;
import com.beeline.workflow.persistence.repository.ActivityResultRepository;
import com.beeline.workflow.persistence.repository.EventRepository;
import com.beeline.workflow.persistence.repository.RetryRepository;
import com.beeline.workflow.persistence.repository.WorkflowRepository;
import com.beeline.workflow.web.dto.EventDto;
import com.beeline.workflow.web.dto.PageResponse;
import com.beeline.workflow.web.dto.PendingActivityDto;
import com.beeline.workflow.web.dto.WorkflowDetailDto;
import com.beeline.workflow.web.dto.WorkflowSummaryDto;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class WorkflowQueryService {

    private final WorkflowRepository workflowRepository;
    private final EventRepository eventRepository;
    private final ActivityResultRepository activityResultRepository;
    private final RetryRepository retryRepository;

    public WorkflowQueryService(WorkflowRepository workflowRepository,
                                EventRepository eventRepository,
                                ActivityResultRepository activityResultRepository,
                                RetryRepository retryRepository) {
        this.workflowRepository = workflowRepository;
        this.eventRepository = eventRepository;
        this.activityResultRepository = activityResultRepository;
        this.retryRepository = retryRepository;
    }

    public PageResponse<WorkflowSummaryDto> search(List<WorkflowStatus> statuses,
                                                   String workflowType,
                                                   String idText,
                                                   Instant from,
                                                   Instant to,
                                                   String quick,
                                                   int page,
                                                   int size,
                                                   String sort) {
        Instant effFrom = from;
        Instant effTo = to;
        List<WorkflowStatus> effStatuses = statuses;

        if (quick != null) {
            Instant now = Instant.now();
            switch (quick.toLowerCase()) {
                case "running" -> effStatuses = List.of(WorkflowStatus.RUNNING, WorkflowStatus.PENDING);
                case "today" -> effFrom = LocalDate.now(ZoneOffset.UTC).atStartOfDay().toInstant(ZoneOffset.UTC);
                case "last-hour", "lasthour" -> effFrom = now.minus(Duration.ofHours(1));
                default -> { /* "all" or unknown -> no-op */ }
            }
        }

        String typeFilter = blankToNull(workflowType);
        Long idFilter = parseId(idText);
        List<WorkflowStatus> statusFilter = (effStatuses != null && !effStatuses.isEmpty()) ? effStatuses : null;

        Specification<WorkflowInstance> spec = buildSpec(statusFilter, typeFilter, idFilter, effFrom, effTo);
        PageRequest pageable = PageRequest.of(page, size, parseSort(sort));

        Page<WorkflowInstance> result = workflowRepository.findAll(spec, pageable);
        return PageResponse.of(result, WorkflowQueryService::toSummary);
    }

    private static Specification<WorkflowInstance> buildSpec(List<WorkflowStatus> statuses,
                                                             String workflowType,
                                                             Long id,
                                                             Instant from,
                                                             Instant to) {
        return (root, query, cb) -> {
            List<jakarta.persistence.criteria.Predicate> preds = new ArrayList<>();
            if (statuses != null && !statuses.isEmpty()) {
                preds.add(root.get("status").in(statuses));
            }
            if (workflowType != null) {
                preds.add(cb.equal(root.get("workflowType"), workflowType));
            }
            if (id != null) {
                preds.add(cb.equal(root.get("id"), id));
            }
            if (from != null) {
                preds.add(cb.greaterThanOrEqualTo(root.get("createdAt"), from));
            }
            if (to != null) {
                preds.add(cb.lessThanOrEqualTo(root.get("createdAt"), to));
            }
            return preds.isEmpty() ? cb.conjunction() : cb.and(preds.toArray(new jakarta.persistence.criteria.Predicate[0]));
        };
    }

    public List<String> workflowTypes() {
        return workflowRepository.findAllWorkflowTypes();
    }

    public Optional<WorkflowDetailDto> detail(Long id) {
        return workflowRepository.findById(id).map(WorkflowQueryService::toDetail);
    }

    public List<EventDto> events(Long workflowId) {
        return eventRepository.findByWorkflowIdOrderByCreatedAtAsc(workflowId).stream()
                .map(WorkflowQueryService::toEvent)
                .toList();
    }

    public List<PendingActivityDto> pendingActivities(Long workflowId) {
        List<ActivityResult> results = activityResultRepository.findByWorkflowIdOrderByCreatedAtAsc(workflowId);
        Map<String, ActivityResult> byName = new HashMap<>();
        for (ActivityResult r : results) {
            byName.merge(r.getActivityName(), r, (a, b) -> a.getCreatedAt().isAfter(b.getCreatedAt()) ? a : b);
        }
        List<RetryRecord> retries = retryRepository.findByWorkflowIdAndProcessedFalseOrderByFireAtAsc(workflowId);

        List<PendingActivityDto> out = new java.util.ArrayList<>();
        // Pending = either active retry record, or last result is in non-terminal state.
        for (RetryRecord r : retries) {
            ActivityResult last = byName.get(r.getActivityName());
            out.add(new PendingActivityDto(
                    r.getActivityName(),
                    r.getAttempt(),
                    r.getMaxAttempts(),
                    r.getFireAt(),
                    last != null ? last.getError() : r.getReason(),
                    last != null ? last.getStatus() : "RETRY_SCHEDULED"
            ));
        }
        // Also surface activities that are FAILED/DEAD without a pending retry — they are "stuck".
        for (ActivityResult r : results) {
            if ("DEAD".equals(r.getStatus())) {
                boolean hasRetry = retries.stream().anyMatch(rr -> rr.getActivityName().equals(r.getActivityName()));
                if (!hasRetry) {
                    out.add(new PendingActivityDto(
                            r.getActivityName(),
                            r.getAttempt(),
                            r.getAttempt(),
                            null,
                            r.getError(),
                            "DEAD"
                    ));
                }
            }
        }
        return out;
    }

    private Sort parseSort(String sort) {
        if (sort == null || sort.isBlank()) return Sort.by(Sort.Direction.DESC, "createdAt");
        String[] parts = sort.split(",");
        String field = switch (parts[0]) {
            case "startTime" -> "createdAt";
            case "endTime" -> "completedAt";
            case "type" -> "workflowType";
            case "status" -> "status";
            case "id" -> "id";
            default -> "createdAt";
        };
        Sort.Direction dir = parts.length > 1 && "asc".equalsIgnoreCase(parts[1])
                ? Sort.Direction.ASC : Sort.Direction.DESC;
        return Sort.by(dir, field);
    }

    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v;
    }

    private static Long parseId(String v) {
        if (v == null || v.isBlank()) return null;
        try { return Long.parseLong(v.trim()); } catch (NumberFormatException e) { return null; }
    }

    private static WorkflowSummaryDto toSummary(WorkflowInstance w) {
        Long dur = w.getCompletedAt() != null
                ? Duration.between(w.getCreatedAt(), w.getCompletedAt()).toMillis()
                : null;
        return new WorkflowSummaryDto(
                w.getId(), w.getWorkflowType(), w.getStatus(),
                w.getCreatedAt(), w.getCompletedAt(), dur);
    }

    private static WorkflowDetailDto toDetail(WorkflowInstance w) {
        Long dur = w.getCompletedAt() != null
                ? Duration.between(w.getCreatedAt(), w.getCompletedAt()).toMillis()
                : null;
        return new WorkflowDetailDto(
                w.getId(), w.getWorkflowType(), w.getStatus(),
                w.getCreatedAt(), w.getCompletedAt(), dur,
                w.getInput(), w.getResult(), w.getError());
    }

    private static EventDto toEvent(Event e) {
        return new EventDto(e.getId(), e.getEventType(), e.getActivityName(),
                e.getAttempt(), e.getData(), e.getCreatedAt());
    }
}
