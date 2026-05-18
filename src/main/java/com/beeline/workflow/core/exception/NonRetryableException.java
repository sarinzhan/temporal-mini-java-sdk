package com.beeline.workflow.core.exception;

public class NonRetryableException extends WorkflowException {
    public NonRetryableException(String message) { super(message); }
    public NonRetryableException(String message, Throwable cause) { super(message, cause); }
}
