package com.beeline.workflow.example;

import com.beeline.workflow.core.api.Worker;
import com.beeline.workflow.sam.api.WorkflowClient;
import com.beeline.workflow.core.api.WorkflowEngine;
import com.beeline.workflow.registry.WorkflowRegistryV0;

public class Main {
    public static void main(String[] args) {
        WorkflowRegistryV0 registry = new WorkflowRegistryV0();

        // 1. регистрация (как ты хотел — явно)
        registry.register(
                GreetingWorkflow.class,
                GreetingWorkflowImpl.class
        );

        Worker worker = new Worker(registry);
        WorkflowEngine engine = new WorkflowEngine(worker);
        WorkflowClient client = new WorkflowClient(engine);

        // 2. создание workflow
        GreetingWorkflow wf =
                client.newWorkflowStub(GreetingWorkflow.class);

        // 3. вызов
        Object result = wf.greet("John");

        System.out.println(result);
    }
}
