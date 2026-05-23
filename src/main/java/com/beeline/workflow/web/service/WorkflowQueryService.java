package com.beeline.workflow.web.service;

import com.beeline.workflow.core.model.Event;
import com.beeline.workflow.core.model.EventType;
import com.beeline.workflow.core.model.RetryRecord;
import com.beeline.workflow.core.model.WorkflowInstance;
import com.beeline.workflow.core.model.WorkflowStatus;
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
    private final RetryRepository retryRepository;

    public WorkflowQueryService(WorkflowRepository workflowRepository,
                                EventRepository eventRepository,
                                RetryRepository retryRepository) {
        this.workflowRepository = workflowRepository;
        this.eventRepository = eventRepository;
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
        return eventRepository.findByWorkflowIdOrderByIdAsc(workflowId).stream()
                .map(WorkflowQueryService::toEvent)
                .toList();
    }

    /**
     * Pending = activities that were SCHEDULED but never COMPLETED/FAILED in history,
     * plus open retries from the retry index. Source of truth is the event log.
     */
    public List<PendingActivityDto> pendingActivities(Long workflowId) {
        List<Event> events = eventRepository.findByWorkflowIdOrderByIdAsc(workflowId);

        // last-seen activity state per name, keyed on the latest event
        Map<String, ActivityState> latest = new HashMap<>();
        for (Event e : events) {
            if (e.getActivityName() == null) continue;
            switch (e.getEventType()) {
                case ACTIVITY_SCHEDULED -> latest.merge(e.getActivityName(),
                        new ActivityState("SCHEDULED", 1, null, null),
                        (prev, neu) -> new ActivityState("SCHEDULED", prev.attempt, prev.lastError, prev.nextFireAt));
                case ACTIVITY_STARTED -> latest.computeIfPresent(e.getActivityName(),
                        (k, prev) -> new ActivityState("STARTED", prev.attempt, prev.lastError, prev.nextFireAt));
                case ACTIVITY_COMPLETED, ACTIVITY_FAILED -> latest.remove(e.getActivityName());
                case ACTIVITY_RETRY_SCHEDULED -> latest.compute(e.getActivityName(),
                        (k, prev) -> new ActivityState("RETRY_SCHEDULED",
                                (prev != null ? prev.attempt + 1 : 1),
                                e.getPayload(), null));
                default -> { /* ignore */ }
            }
        }

        List<RetryRecord> openRetries = retryRepository.findByWorkflowIdAndProcessedFalseOrderByFireAtAsc(workflowId);
        Map<String, RetryRecord> retryByName = new HashMap<>();
        for (RetryRecord r : openRetries) {
            retryByName.put(r.getActivityName(), r);
        }

        List<PendingActivityDto> out = new ArrayList<>();
        for (var entry : latest.entrySet()) {
            ActivityState st = entry.getValue();
            RetryRecord retry = retryByName.get(entry.getKey());
            int maxAttempts = retry != null ? retry.getMaxAttempts() : st.attempt;
            Instant nextFireAt = retry != null ? retry.getFireAt() : st.nextFireAt;
            String lastError = retry != null ? retry.getReason() : st.lastError;
            out.add(new PendingActivityDto(
                    entry.getKey(),
                    retry != null ? retry.getAttempt() : st.attempt,
                    maxAttempts,
                    nextFireAt,
                    lastError,
                    st.status
            ));
        }
        return out;
    }

    private record ActivityState(String status, int attempt, String lastError, Instant nextFireAt) {}

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
        return new WorkflowSummaryDto(
                w.getId(), w.getWorkflowType(), w.getStatus(),
                w.getCreatedAt(), w.getCompletedAt(), computeDuration(w));
    }

    private static WorkflowDetailDto toDetail(WorkflowInstance w) {
        return new WorkflowDetailDto(
                w.getId(), w.getWorkflowType(), w.getStatus(),
                w.getCreatedAt(), w.getCompletedAt(), computeDuration(w),
                w.getInput(), w.getResult(), w.getError());
    }

    private static Long computeDuration(WorkflowInstance w) {
        if (w.getCreatedAt() == null) return null;
        Instant end = w.getCompletedAt() != null ? w.getCompletedAt() : Instant.now();
        return Duration.between(w.getCreatedAt(), end).toMillis();
    }

    private static EventDto toEvent(Event e) {
        return new EventDto(
                e.getId(),
                e.getEventType(),
                e.getCommandType(),
                e.getSeq(),
                e.getActivityName(),
                e.getPayload(),
                e.getCreatedAt());
    }
}
