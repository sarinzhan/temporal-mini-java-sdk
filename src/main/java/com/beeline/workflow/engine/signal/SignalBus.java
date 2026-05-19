package com.beeline.workflow.engine.signal;

import java.time.Duration;

public interface SignalBus {

    void send(Long workflowId, String signalName, Object payload);

    Object await(Long workflowId, String signalName, Duration timeout);
}
