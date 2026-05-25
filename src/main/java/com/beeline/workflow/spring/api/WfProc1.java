package com.beeline.workflow.spring.api;

@FunctionalInterface
public interface WfProc1<A> {
    void apply(A arg);
}
