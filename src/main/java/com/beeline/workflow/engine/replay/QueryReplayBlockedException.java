package com.beeline.workflow.engine.replay;

/**
 * Thrown by Workflow.* commands during query replay when the workflow has reached
 * a point past existing history (i.e. would have to side-effect or block). The
 * query runtime catches this as a signal to stop replay and invoke the query
 * method against the workflow state as accumulated so far.
 */
public class QueryReplayBlockedException extends RuntimeException {
    public QueryReplayBlockedException(String msg) {
        super(msg);
    }
}
