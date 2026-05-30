package com.beeline.workflow.engine.replay;

import com.beeline.workflow.engine.codec.PayloadCodec;

/**
 * Produces a per-turn, in-memory {@link EventLogImpl} bound to a (workflowId, lease). The log
 * buffers events for the turn; persistence happens atomically at commit time via
 * {@code TurnCommitter}, so this factory no longer needs repositories or a transaction manager.
 */
public final class EventLogFactory {

    private final PayloadCodec codec;

    public EventLogFactory(PayloadCodec codec) {
        this.codec = codec;
    }

    public EventLogImpl create(Long workflowId, TaskLease lease) {
        return new EventLogImpl(workflowId, lease, codec);
    }
}
