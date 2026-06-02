package com.beeline.workflow.sam.core.engine;

import com.beeline.workflow.sam.api.annotation.WorkflowImpl;
import com.beeline.workflow.sam.api.annotation.WorkflowInterface;
import com.beeline.workflow.sam.api.annotation.WorkflowMethod;
import com.beeline.workflow.sam.api.WorkflowRegistry;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

public class WorkflowRegisterImpl implements WorkflowRegistry {

    private Map<String, Impl> workflows = new HashMap<>();


    @Override
    public void register(Class<?> clazz, Class<?> interfaze) {
        if(!interfaze.isAnnotationPresent(WorkflowInterface.class)){
            throw new IllegalArgumentException("Couldn't register interface " + interfaze.getSimpleName() + " to workflow registry. Must have annotation @WorkflowInterface.");
        }

        if(!clazz.isAnnotationPresent(WorkflowImpl.class)){
            throw new IllegalArgumentException("Couldn't register class " + clazz.getSimpleName() + " to workflow registry. Must have annotation @WorkflowImpl.");
        }


        Method[] interfaceMethods = interfaze.getMethods();
        if(interfaceMethods.length > 1){
            throw new IllegalArgumentException("Workflow interface " + interfaze.getSimpleName() + " must have only one method.");
        }

        Method interfaceMethod = interfaceMethods[0];
        if(interfaceMethod.isAnnotationPresent(WorkflowMethod.class)){
            throw new IllegalArgumentException("Workflow interface " + interfaze.getSimpleName() + " method " + interfaceMethod.getName() + " must have annotation @WorkflowMethod");
        }

        String className = clazz.getSimpleName();
        WorkflowImpl annotation = clazz.getAnnotation(WorkflowImpl.class);
        if(!annotation.value().isBlank()){
            className = annotation.value();
        }

        workflows.put(className, new Impl(clazz, interfaze));
    }


    private class Impl{
        private Class<?> implClass;
        private Class<?> interfaze;

        public Impl(Class<?> clazz, Class<?> interfaze){
            this.implClass = clazz;
            this.interfaze = interfaze;
        }
    }

//    @Override
//    public WorkflowInterface<?, ?> get(String type) {
//        WorkflowInterface<?, ?> workflowInterface = workflows.get(type);
//
//        if(workflowInterface == null){
//            throw new IllegalArgumentException("Workflow type " + type + " not found in registry");
//        }
//
//        return workflowInterface;
//    }
}
