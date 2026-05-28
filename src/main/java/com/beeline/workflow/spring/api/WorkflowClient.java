package com.beeline.workflow.spring.api;

public interface WorkflowClient {

    /**
     * Start a workflow by its {@code @WorkflowInterface} type. The input is serialized and stored;
     * the returned handle deserializes the result against the entry method's return type.
     */
    <R> WorkflowHandle<R> start(Class<?> workflowInterface, Object input);

    /**
     * Start a workflow by its registered type name, with the input already serialized to JSON.
     * Returns the new workflow id. Intended for the REST layer.
     */
    Long startByType(String workflowType, String inputJson);
}
