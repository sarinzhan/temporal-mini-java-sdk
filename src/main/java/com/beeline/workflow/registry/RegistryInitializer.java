package com.beeline.workflow.registry;

import com.beeline.workflow.core.annotation.Activity;
import com.beeline.workflow.core.annotation.QueryMethod;
import com.beeline.workflow.core.annotation.UpdateMethod;
import com.beeline.workflow.core.annotation.WorkflowComponent;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.framework.AopProxyUtils;
import org.springframework.context.ApplicationContext;
import org.springframework.util.ClassUtils;

import java.lang.reflect.Method;
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
                WorkflowComponent ann = beanClass.getAnnotation(WorkflowComponent.class);
                String type = (ann.value() == null || ann.value().isBlank())
                        ? beanClass.getSimpleName() : ann.value();
                workflowRegistry.register(type, bean);
                log.info("Registered workflow: {} -> {}", type, beanClass.getName());

                scanQueryAndUpdateMethods(type, beanClass);
            }
        }
        log.info("RegistryInitializer: {} activities, {} workflows registered",
                activityRegistry.size(), workflowRegistry.size());
    }

    private void scanQueryAndUpdateMethods(String workflowType, Class<?> beanClass) {
        for (Method m : beanClass.getDeclaredMethods()) {
            if (m.isSynthetic() || m.isBridge()) continue;
            QueryMethod query = m.getAnnotation(QueryMethod.class);
            if (query != null) {
                String name = query.name().isBlank() ? m.getName() : query.name();
                workflowRegistry.registerQuery(workflowType, name, m);
                log.info("Registered query: {}::{} -> {}", workflowType, name, m.getName());
            }
            UpdateMethod update = m.getAnnotation(UpdateMethod.class);
            if (update != null) {
                String name = update.name().isBlank() ? m.getName() : update.name();
                workflowRegistry.registerUpdate(workflowType, name, m);
                log.info("Registered update: {}::{} -> {}", workflowType, name, m.getName());
            }
        }
    }
}
