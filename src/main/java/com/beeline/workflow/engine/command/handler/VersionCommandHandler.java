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

        // No marker. If in replay, this is an old workflow that pre-dates the call.
        if (state.isInReplay()) {
            return Workflow.DEFAULT_VERSION;
        }

        int seq = state.nextSeq();
        ctx.eventLog().versionMarker(seq, cmd.changeId(), cmd.maxSupported());
        return cmd.maxSupported();
    }
}
