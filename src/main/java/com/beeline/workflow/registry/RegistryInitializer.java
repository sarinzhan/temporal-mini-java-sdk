package com.beeline.workflow.registry;

import com.beeline.workflow.core.annotation.WorkflowImpl;
import com.beeline.workflow.core.annotation.WorkflowInterface;
import com.beeline.workflow.core.annotation.WorkflowMethod;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.util.ClassUtils;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Scans Spring beans for {@code @WorkflowComponent} and registers each workflow type, its
 * {@code @WorkflowInterface}, and its single {@code @WorkflowMethod} entry point. Activities are
 * plain inline lambdas now, so there is no activity registry to populate.
 */
public class RegistryInitializer {

    private static final Logger log = LoggerFactory.getLogger(RegistryInitializer.class);

    private final ApplicationContext applicationContext;
    private final WorkflowRegistry workflowRegistry;

    public RegistryInitializer(ApplicationContext applicationContext,
                               WorkflowRegistry workflowRegistry) {
        this.applicationContext = applicationContext;
        this.workflowRegistry = workflowRegistry;
    }

    @PostConstruct
    public void init() {
        // Look up @WorkflowComponent bean definitions by name instead of materializing every bean
        // in the context — these are prototype-scoped, and instantiating them eagerly here would
        // defeat the point. A fresh instance is created per turn in WorkflowExecutor instead.
        Map<String, Object> beans = applicationContext.getBeansWithAnnotation(WorkflowInterface.class);
        for (String beanName : beans) {
            Class<?> beanClass = applicationContext.getType(beanName);
            if (beanClass == null) continue;

            Set<Class<?>> interfaces = ClassUtils.getAllInterfacesForClassAsSet(beanClass);
            Class<?> workflowIface = findWorkflowInterface(beanClass, interfaces);
            WorkflowImpl componentAnn = beanClass.getAnnotation(WorkflowImpl.class);
            String type = resolveWorkflowType(componentAnn, workflowIface);

            workflowRegistry.register(type, beanClass);
            workflowRegistry.registerInterface(type, workflowIface);
            registerEntry(type, workflowIface);
            log.info("Registered workflow: {} -> impl={} iface={}", type, beanClass.getName(), workflowIface.getName());
        }
        log.info("RegistryInitializer: {} workflows registered", workflowRegistry.size());
    }

    private Class<?> findWorkflowInterface(Class<?> beanClass, Set<Class<?>> interfaces) {
        List<Class<?>> wfInterfaces = new ArrayList<>();
        for (Class<?> iface : interfaces) {
            if (iface.isAnnotationPresent(WorkflowInterface.class)) {
                wfInterfaces.add(iface);
            }
        }
        if (wfInterfaces.isEmpty()) {
            throw new IllegalStateException(
                    "@WorkflowComponent " + beanClass.getName() +
                    " does not implement any @WorkflowInterface. " +
                    "Define an interface annotated with @WorkflowInterface and implement it.");
        }
        if (wfInterfaces.size() > 1) {
            throw new IllegalStateException(
                    "@WorkflowComponent " + beanClass.getName() +
                    " implements multiple @WorkflowInterface interfaces: " + wfInterfaces +
                    ". Only one is allowed.");
        }
        return wfInterfaces.get(0);
    }

    private String resolveWorkflowType(WorkflowImpl componentAnn, Class<?> workflowIface) {
        if (componentAnn != null && componentAnn.value() != null && !componentAnn.value().isBlank()) {
            return componentAnn.value();
        }
        WorkflowInterface ifaceAnn = workflowIface.getAnnotation(WorkflowInterface.class);
        if (ifaceAnn != null && ifaceAnn.value() != null && !ifaceAnn.value().isBlank()) {
            return ifaceAnn.value();
        }
        return workflowIface.getSimpleName();
    }

    private void registerEntry(String workflowType, Class<?> workflowIface) {
        Method entry = null;
        for (Method m : workflowIface.getMethods()) {
            if (m.isSynthetic() || m.isBridge()) continue;
            if (m.isAnnotationPresent(WorkflowMethod.class)) {
                if (entry != null) {
                    throw new IllegalStateException(
                            "@WorkflowInterface " + workflowIface.getName() +
                            " declares multiple @WorkflowMethod methods: " + entry + " and " + m +
                            ". Exactly one is allowed.");
                }
                entry = m;
            }
        }
        if (entry == null) {
            throw new IllegalStateException(
                    "@WorkflowInterface " + workflowIface.getName() +
                    " has no @WorkflowMethod. Mark exactly one method as the workflow entry.");
        }
        workflowRegistry.registerEntry(workflowType, entry);
        log.info("Registered entry: {}::{}", workflowType, entry.getName());
    }
}
