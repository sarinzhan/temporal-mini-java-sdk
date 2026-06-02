package com.beeline.workflow.sam.client;

import com.beeline.workflow.core.api.WorkflowEngine;
import com.beeline.workflow.sam.api.WorkflowClient;

public class WorkflowClientImpl implements WorkflowClient {
    private final WorkflowEngine engine;

    public WorkflowClient(WorkflowEngine engine) {
        this.engine = engine;
    }

    @SuppressWarnings("unchecked")
    public <T> T newWorkflowStub(Class<T> iface) {

        return (T) java.lang.reflect.Proxy.newProxyInstance(
                iface.getClassLoader(),
                new Class[]{iface},
                (proxy, method, args) -> {


                    method.invoke(proxy, args);

                    // любой вызов метода = старт workflow
                    return engine.startWorkflow(
                            iface,
                            method.getName(),
                            args
                    );
                }
        );
    }
}
