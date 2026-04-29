package com.beeline.temporalmini;

public interface Workflow {
    String type();
    void run(WorkflowContext ctx);
}
