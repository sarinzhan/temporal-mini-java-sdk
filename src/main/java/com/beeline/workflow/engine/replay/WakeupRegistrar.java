package com.beeline.workflow.engine.replay;

import java.time.Instant;

/**
 * Inserts wake-up index rows (pending_timers / pending_awaits) so the {@code WakeupScheduler}
 * can find and fire them. Source of truth for state is still the {@code events} log — these
 * rows are an index for efficient polling.
 */
public interface WakeupRegistrar {

    void registerTimer(int seq, Instant fireAt);

    /** {@code deadline} may be null to mean "wait for signal only, no timeout". */
    void registerAwait(int seq, Instant deadline);

    /** Remove a pending await row once the workflow has emitted AWAIT_FIRED on its own. */
    void deleteAwait(int seq);
}
