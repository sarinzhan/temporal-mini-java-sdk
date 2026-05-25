package com.beeline.workflow.spring.api;

import com.beeline.workflow.core.config.WorkflowOptions;

public interface WorkflowClient {

    <T> T newWorkflowStub(Class<T> iface, WorkflowOptions opts);

    <T> T newWorkflowStub(Class<T> iface, Long instanceId);

    <A, R> WorkflowHandle<R> start(WfFunc1<A, R> fn, A arg);

    <R> WorkflowHandle<R> start(WfFunc0<R> fn);

    <A> WorkflowHandle<Void> start(WfProc1<A> fn, A arg);

    WorkflowHandle<Void> start(WfProc0 fn);
}
