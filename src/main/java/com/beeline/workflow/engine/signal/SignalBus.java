package com.beeline.workflow.engine.signal;

/**
 * Delivers external signals to a workflow. {@code send} records a {@code SIGNAL_RECEIVED}
 * event and nudges the workflow so a parked {@code Workflow.await(condition)} re-evaluates.
 * Signals are consumed inside the workflow turn by {@code @SignalMethod} handlers — there is
 * no blocking await on this bus.
 */
public interface SignalBus {

    void send(Long workflowId, String signalName, Object payload);
}
