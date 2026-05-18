package com.beeline.workflow.registry;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class WorkflowRegistry {

    private final Map<String, Object> beansByType = new ConcurrentHashMap<>();

    public void register(String workflowType, Object bean) {
        beansByType.put(workflowType, bean);
    }

    public Object getBean(String workflowType) {
        return beansByType.get(workflowType);
    }

    public boolean contains(String workflowType) {
        return beansByType.containsKey(workflowType);
    }

    public int size() {
        return beansByType.size();
    }
}
