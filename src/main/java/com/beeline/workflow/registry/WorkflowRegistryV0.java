package com.beeline.workflow.registry;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class WorkflowRegistryV0 {

    private Map<Class<?>, Class<?>> workflows = new ConcurrentHashMap<>();


    public void register(Class<?> iface, Class<?> impl){
        if (!iface.isInterface()) {
            throw new IllegalArgumentException("Workflow type must be interface");
        }

        if (!iface.isAssignableFrom(impl)) {
            throw new IllegalArgumentException(
                    impl.getName() + " does not implement " + iface.getName()
            );
        }

        workflows.put(iface, impl);
    }


    public Class<?> getImpl(Class<?> iface) {
        return workflows.get(iface);
    }

}
