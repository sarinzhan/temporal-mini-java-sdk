package com.beeline.workflow.registry;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class WorkflowRegistry {

    private final Map<String, Object> beansByType = new ConcurrentHashMap<>();
    private final Map<String, Class<?>> classesByType = new ConcurrentHashMap<>();
    private final Map<Class<?>, String> typeByInterface = new ConcurrentHashMap<>();
    private final Map<String, Class<?>> interfaceByType = new ConcurrentHashMap<>();
    private final Map<String, Method> entryMethodByType = new ConcurrentHashMap<>();

    public void register(String workflowType, Object bean) {
        beansByType.put(workflowType, bean);
        classesByType.put(workflowType, bean.getClass());
    }

    public void registerInterface(String workflowType, Class<?> iface) {
        typeByInterface.put(iface, workflowType);
        interfaceByType.put(workflowType, iface);
    }

    public void registerEntry(String workflowType, Method entry) {
        entryMethodByType.put(workflowType, entry);
    }

    public Class<?> getInterfaceForType(String workflowType) {
        return interfaceByType.get(workflowType);
    }

    public String getTypeForInterface(Class<?> iface) {
        return typeByInterface.get(iface);
    }

    public Method getEntryMethod(String workflowType) {
        return entryMethodByType.get(workflowType);
    }

    public Object getBean(String workflowType) {
        return beansByType.get(workflowType);
    }

    public Class<?> getBeanClass(String workflowType) {
        return classesByType.get(workflowType);
    }

    public boolean contains(String workflowType) {
        return beansByType.containsKey(workflowType);
    }

    public int size() {
        return beansByType.size();
    }
}
