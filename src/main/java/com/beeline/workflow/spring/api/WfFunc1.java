package com.beeline.workflow.spring.api;

@FunctionalInterface
public interface WfFunc1<A, R> {
    R apply(A arg);
}
