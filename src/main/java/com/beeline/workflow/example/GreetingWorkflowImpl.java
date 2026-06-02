package com.beeline.workflow.example;

public class GreetingWorkflowImpl implements GreetingWorkflow{
    @Override
    public String greet(String name) {
        return "Hello " + name;
    }
}
