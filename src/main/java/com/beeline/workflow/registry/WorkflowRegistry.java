package com.beeline.workflow.registry;

import org.springframework.context.ApplicationContext;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class WorkflowRegistry {

    private final ApplicationContext applicationContext;

    private final Map<String, Class<?>> classesByType = new ConcurrentHashMap<>();
    private final Map<Class<?>, String> typeByInterface = new ConcurrentHashMap<>();
    private final Map<String, Class<?>> interfaceByType = new ConcurrentHashMap<>();
    private final Map<String, Method> entryMethodByType = new ConcurrentHashMap<>();

    public WorkflowRegistry(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    public void register(String workflowType, Class<?> beanClass) {
        classesByType.put(workflowType, beanClass);
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

    /**
     * Returns a fresh prototype instance of the workflow bean. Each decision turn must call this
     * to get its own object, so concurrent turns of the same workflow type don't share mutable
     * fields. State across turns is reconstructed from the event history via replay, not from
     * the bean instance itself.
     */
    public Object createInstance(String workflowType) {
        Class<?> beanClass = classesByType.get(workflowType);
        if (beanClass == null) return null;
        return applicationContext.getBean(beanClass);
    }

    public Class<?> getBeanClass(String workflowType) {
        return classesByType.get(workflowType);
    }

    public boolean contains(String workflowType) {
        return classesByType.containsKey(workflowType);
    }

    public int size() {
        return classesByType.size();
    }
}
