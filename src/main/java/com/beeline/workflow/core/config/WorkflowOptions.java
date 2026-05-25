package com.beeline.workflow.core.config;

public final class WorkflowOptions {

    private final String workflowId;

    private WorkflowOptions(Builder b) {
        this.workflowId = b.workflowId;
    }

    public String getWorkflowId() { return workflowId; }

    public static WorkflowOptions defaultOptions() {
        return newBuilder().build();
    }

    public static Builder newBuilder() {
        return new Builder();
    }

    public static final class Builder {
        private String workflowId;

        public Builder setWorkflowId(String v) { this.workflowId = v; return this; }
        public Builder workflowId(String v) { this.workflowId = v; return this; }

        public WorkflowOptions build() { return new WorkflowOptions(this); }
    }
}
