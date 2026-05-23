package com.beeline.workflow.engine.replay;

import com.beeline.workflow.core.model.EventType;

/**
 * Persists workflow events during a decision turn. Each call commits in its own transaction,
 * matching the per-event commit policy used by {@code ActivityExecutorImpl}.
 */
public interface EventSink {

    void append(EventType type,
                CommandType commandType,
                Integer seq,
                String activityName,
                String payload);
}
