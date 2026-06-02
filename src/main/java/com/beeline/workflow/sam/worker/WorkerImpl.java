package com.beeline.workflow.sam.worker;

import com.beeline.workflow.sam.api.Worker;
import com.beeline.workflow.sam.api.annotation.WorkflowInterface;
import com.beeline.workflow.sam.api.WorkflowRegistry;

public class WorkerImpl implements Worker {
    private WorkflowRegistry workflowRegistry;

    @Override
    public void execute(WorkflowInterface<?, ?> workflow, Object[] args) {
        workflowRegistry.get()
    }
}
