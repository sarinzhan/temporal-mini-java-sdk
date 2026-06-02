package com.beeline.workflow.sam.api;

import com.beeline.workflow.sam.api.annotation.WorkflowInterface;

public interface Worker {
        void execute(WorkflowInterface<?, ?> workflow, Object[] args);
        Worker init(String queue);
        void register()
}
