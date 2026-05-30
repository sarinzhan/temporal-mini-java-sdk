package com.beeline.workflow.engine.command.handler;

import com.beeline.workflow.core.api.Workflow;
import com.beeline.workflow.engine.command.CommandContext;
import com.beeline.workflow.engine.command.CommandHandler;
import com.beeline.workflow.engine.command.VersionCommand;
import com.beeline.workflow.engine.replay.ReplayState;

import java.util.OptionalInt;

public final class VersionCommandHandler implements CommandHandler<VersionCommand> {

    @Override
    public Class<VersionCommand> commandClass() {
        return VersionCommand.class;
    }

    @Override
    public Object handle(VersionCommand cmd, CommandContext ctx) {
        if (cmd.minSupported() > cmd.maxSupported()) {
            throw new IllegalArgumentException(
                    "minSupported > maxSupported: " + cmd.minSupported() + " > " + cmd.maxSupported());
        }
        ReplayState state = ctx.replayState();

        // Version markers are keyed by changeId for the whole workflow, NOT by the per-command seq,
        // and they deliberately do NOT consume the shared seq counter. The old code consumed a seq
        // only on the write path but never on the replay-read path, so activity/sideEffect seq
        // numbers drifted between the original turn and replay and triggered spurious
        // NonDeterminismExceptions. Keeping getVersion out of the seq sequence makes it symmetric.
        OptionalInt existing = state.findVersion(cmd.changeId());
        if (existing.isPresent()) {
            int v = existing.getAsInt();
            if (v < cmd.minSupported()) {
                throw new IllegalStateException(
                        "Workflow on too-old version for changeId=" + cmd.changeId()
                                + ": " + v + " < " + cmd.minSupported());
            }
            return v;
        }

        // No marker for this changeId yet. If the workflow already ran past this point in an earlier
        // turn (there is recorded command history ahead), this is old code that pre-dates the change
        // → take the legacy path. Otherwise we are at the tip of history: record the marker once.
        if (state.cursor().hasCommandsAhead()) {
            return Workflow.DEFAULT_VERSION;
        }
        ctx.eventLog().versionMarker(cmd.changeId(), cmd.maxSupported());
        return cmd.maxSupported();
    }
}
