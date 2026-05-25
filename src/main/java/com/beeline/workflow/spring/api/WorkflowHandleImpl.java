package com.beeline.workflow.spring.api;

import com.beeline.workflow.core.model.WorkflowInstance;
import com.beeline.workflow.core.model.WorkflowStatus;
import com.beeline.workflow.persistence.repository.WorkflowRepository;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.ObjectMapper;

import java.lang.reflect.Type;

public class WorkflowHandleImpl<T> implements WorkflowHandle<T> {

    private static final long POLL_INTERVAL_MS = 50L;

    private final Long instanceId;
    private final String workflowType;
    private final Type resultType;
    private final WorkflowRepository workflowRepository;
    private final ObjectMapper objectMapper;

    public WorkflowHandleImpl(Long instanceId,
                              String workflowType,
                              Type resultType,
                              WorkflowRepository workflowRepository,
                              ObjectMapper objectMapper) {
        this.instanceId = instanceId;
        this.workflowType = workflowType;
        this.resultType = resultType;
        this.workflowRepository = workflowRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    public Long getInstanceId() { return instanceId; }

    @Override
    public String getWorkflowType() { return workflowType; }

    @Override
    public WorkflowStatus getStatus() {
        return workflowRepository.findById(instanceId)
                .map(WorkflowInstance::getStatus)
                .orElse(null);
    }

    @Override
    @SuppressWarnings("unchecked")
    public T getResult(long timeoutMs) {
        long deadline = System.currentTimeMillis() + Math.max(0L, timeoutMs);
        while (true) {
            WorkflowInstance wf = workflowRepository.findById(instanceId).orElse(null);
            if (wf == null) {
                throw new IllegalStateException("workflow not found: " + instanceId);
            }
            WorkflowStatus s = wf.getStatus();
            if (s == WorkflowStatus.COMPLETED) {
                if (resultType == void.class || resultType == Void.class) return null;
                String json = wf.getResult();
                if (json == null) return null;
                try {
                    JavaType jt = objectMapper.constructType(resultType);
                    return (T) objectMapper.readValue(json, jt);
                } catch (Exception e) {
                    throw new RuntimeException("Failed to deserialize workflow result", e);
                }
            }
            if (s == WorkflowStatus.FAILED) {
                throw new RuntimeException("workflow failed: " + wf.getError());
            }
            if (s == WorkflowStatus.CANCELLED) {
                throw new RuntimeException("workflow cancelled");
            }
            if (System.currentTimeMillis() >= deadline) {
                throw new RuntimeException(
                        "workflow result timeout after " + timeoutMs + "ms, status=" + s);
            }
            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("interrupted while waiting for workflow result", ie);
            }
        }
    }
}
