package com.beeline.workflow.engine.command.handler;

import com.beeline.workflow.engine.command.CommandContext;
import com.beeline.workflow.engine.command.CommandHandler;
import com.beeline.workflow.engine.command.SideEffectCommand;
import com.beeline.workflow.engine.replay.CommandType;
import com.beeline.workflow.engine.replay.ReplayState;

import java.util.Optional;

public final class SideEffectCommandHandler implements CommandHandler<SideEffectCommand> {

    @Override
    public Class<SideEffectCommand> commandClass() {
        return SideEffectCommand.class;
    }

    @Override
    public Object handle(SideEffectCommand cmd, CommandContext ctx) {
        ReplayState state = ctx.replayState();
        int seq = state.nextSeq();
        state.assertCommandTypeMatches(seq, CommandType.SIDE_EFFECT);

        Optional<String> recorded = state.findSideEffectPayload(seq);
        if (recorded.isPresent()) {
            return ctx.codec().decodeSideEffectResult(recorded.get(), cmd.resultType());
        }

        Object value = cmd.body().get();
        ctx.eventLog().sideEffectRecorded(seq, value);
        return value;
    }
}
