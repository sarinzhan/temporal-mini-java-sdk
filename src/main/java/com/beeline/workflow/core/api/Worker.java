package com.beeline.workflow.core.api;

import com.beeline.workflow.registry.WorkflowRegistryV0;

public class Worker {
    private final WorkflowRegistryV0 registry;

    public Worker(WorkflowRegistryV0 registry) {
        this.registry = registry;
    }

    public Object execute(Class<?> iface,
                          String method,
                          Object[] args) {

        try {
            Class<?> implClass = registry.getImpl(iface);

            Object impl = implClass.getDeclaredConstructor()
                    .newInstance();

            for (var m : implClass.getMethods()) {
                if (m.getName().equals(method)) {
                    return m.invoke(impl, args);
                }
            }

            throw new RuntimeException("Method not found");

        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
