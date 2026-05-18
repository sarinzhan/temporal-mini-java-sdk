package com.beeline.workflow.registry;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ActivityRegistry {

    private final Map<Class<?>, Object> beans = new ConcurrentHashMap<>();

    public void register(Class<?> activityInterface, Object bean) {
        if (!activityInterface.isInterface()) {
            throw new IllegalArgumentException("Activity registry key must be an interface: " + activityInterface);
        }
        beans.put(activityInterface, bean);
    }

    @SuppressWarnings("unchecked")
    public <T> T getBean(Class<T> activityInterface) {
        return (T) beans.get(activityInterface);
    }

    public boolean contains(Class<?> activityInterface) {
        return beans.containsKey(activityInterface);
    }

    public int size() {
        return beans.size();
    }
}
