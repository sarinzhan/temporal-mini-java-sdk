package com.beeline.workflow.core.api;

public class WorkflowEngine {
    private final Worker worker;

    public WorkflowEngine(Worker worker) {
        this.worker = worker;
    }

    public Object startWorkflow(Class<?> iface, String method, Object[] args) {

        // в реальности тут была бы очередь
        return worker.execute(iface, method, args);
    }
}
