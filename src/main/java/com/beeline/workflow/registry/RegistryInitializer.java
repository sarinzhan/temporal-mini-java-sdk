package com.beeline.workflow.registry;

import com.beeline.workflow.core.annotation.Activity;
import com.beeline.workflow.core.annotation.QueryMethod;
import com.beeline.workflow.core.annotation.SignalMethod;
import com.beeline.workflow.core.annotation.UpdateMethod;
import com.beeline.workflow.core.annotation.WorkflowComponent;
import com.beeline.workflow.core.annotation.WorkflowInterface;
import com.beeline.workflow.core.annotation.WorkflowMethod;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.framework.AopProxyUtils;
import org.springframework.context.ApplicationContext;
import org.springframework.util.ClassUtils;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class RegistryInitializer {

    private static final Logger log = LoggerFactory.getLogger(RegistryInitializer.class);

    private final ApplicationContext applicationContext;
    private final ActivityRegistry activityRegistry;
    private final WorkflowRegistry workflowRegistry;

    public RegistryInitializer(ApplicationContext applicationContext,
                               ActivityRegistry activityRegistry,
                               WorkflowRegistry workflowRegistry) {
        this.applicationContext = applicationContext;
        this.activityRegistry = activityRegistry;
        this.workflowRegistry = workflowRegistry;
    }

    @PostConstruct
    public void init() {
        for (Object bean : applicationContext.getBeansOfType(Object.class).values()) {
            Class<?> beanClass = AopProxyUtils.ultimateTargetClass(bean);

            Set<Class<?>> interfaces = ClassUtils.getAllInterfacesForClassAsSet(beanClass);
            for (Class<?> iface : interfaces) {
                if (iface.isAnnotationPresent(Activity.class) && !activityRegistry.contains(iface)) {
                    activityRegistry.register(iface, bean);
                    log.info("Registered activity: {} -> {}", iface.getName(), beanClass.getName());
                }
            }

            if (beanClass.isAnnotationPresent(WorkflowComponent.class)) {
                Class<?> workflowIface = findWorkflowInterface(beanClass, interfaces);
                WorkflowComponent componentAnn = beanClass.getAnnotation(WorkflowComponent.class);
                String type = resolveWorkflowType(componentAnn, workflowIface);

                workflowRegistry.register(type, bean);
                workflowRegistry.registerInterface(type, workflowIface);
                log.info("Registered workflow: {} -> impl={} iface={}", type, beanClass.getName(), workflowIface.getName());

                scanInterfaceMethods(type, workflowIface);
            }
        }
        log.info("RegistryInitializer: {} activities, {} workflows registered",
                activityRegistry.size(), workflowRegistry.size());
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

    private String resolveWorkflowType(WorkflowComponent componentAnn, Class<?> workflowIface) {
        if (componentAnn != null && componentAnn.value() != null && !componentAnn.value().isBlank()) {
            return componentAnn.value();
        }
        WorkflowInterface ifaceAnn = workflowIface.getAnnotation(WorkflowInterface.class);
        if (ifaceAnn != null && ifaceAnn.value() != null && !ifaceAnn.value().isBlank()) {
            return ifaceAnn.value();
        }
        return workflowIface.getSimpleName();
    }

    private void scanInterfaceMethods(String workflowType, Class<?> workflowIface) {
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
                continue;
            }

            QueryMethod query = m.getAnnotation(QueryMethod.class);
            if (query != null) {
                String name = query.name().isBlank() ? m.getName() : query.name();
                workflowRegistry.registerQuery(workflowType, name, m);
                log.info("Registered query: {}::{} -> {}", workflowType, name, m.getName());
                continue;
            }

            UpdateMethod update = m.getAnnotation(UpdateMethod.class);
            if (update != null) {
                String name = update.name().isBlank() ? m.getName() : update.name();
                workflowRegistry.registerUpdate(workflowType, name, m);
                log.info("Registered update: {}::{} -> {}", workflowType, name, m.getName());
                continue;
            }

            SignalMethod signal = m.getAnnotation(SignalMethod.class);
            if (signal != null) {
                if (m.getParameterCount() > 1) {
                    throw new IllegalStateException(
                            "@SignalMethod " + workflowIface.getName() + "::" + m.getName() +
                            " must take 0 or 1 parameters, has " + m.getParameterCount());
                }
                String name = signal.name().isBlank() ? m.getName() : signal.name();
                workflowRegistry.registerSignal(workflowType, name, m);
                log.info("Registered signal: {}::{} -> {}", workflowType, name, m.getName());
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
