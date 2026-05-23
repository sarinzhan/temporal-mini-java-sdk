package com.beeline.workflow.engine.replay;

public class NonDeterminismException extends RuntimeException {
    public NonDeterminismException(String message) {
        super(message);
    }
}
