package com.beeline.workflow.engine.signal;

import java.time.Duration;
import java.util.UUID;

public interface SignalBus {

    void send(UUID workflowId, String signalName, Object payload);

    Object await(UUID workflowId, String signalName, Duration timeout);
}
