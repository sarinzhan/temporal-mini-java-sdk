package com.beeline.workflow.engine.replay;

import com.beeline.workflow.core.model.PendingAwait;
import com.beeline.workflow.core.model.PendingTimer;
import com.beeline.workflow.persistence.repository.PendingAwaitRepository;
import com.beeline.workflow.persistence.repository.PendingTimerRepository;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;

public final class WakeupRegistrarImpl implements WakeupRegistrar {

    private final Long workflowId;
    private final PendingTimerRepository timerRepository;
    private final PendingAwaitRepository awaitRepository;
    private final TransactionTemplate transactionTemplate;

    public WakeupRegistrarImpl(Long workflowId,
                               PendingTimerRepository timerRepository,
                               PendingAwaitRepository awaitRepository,
                               TransactionTemplate transactionTemplate) {
        this.workflowId = workflowId;
        this.timerRepository = timerRepository;
        this.awaitRepository = awaitRepository;
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    public void registerTimer(int seq, Instant fireAt) {
        transactionTemplate.executeWithoutResult(s -> {
            if (timerRepository.findByWorkflowIdAndSeq(workflowId, seq).isPresent()) return;
            PendingTimer t = new PendingTimer();
            t.setWorkflowId(workflowId);
            t.setSeq(seq);
            t.setFireAt(fireAt);
            timerRepository.save(t);
        });
    }

    @Override
    public void registerAwait(int seq, Instant deadline) {
        transactionTemplate.executeWithoutResult(s -> {
            if (awaitRepository.findByWorkflowIdAndSeq(workflowId, seq).isPresent()) return;
            PendingAwait a = new PendingAwait();
            a.setWorkflowId(workflowId);
            a.setSeq(seq);
            a.setDeadline(deadline);
            awaitRepository.save(a);
        });
    }

    @Override
    public void deleteAwait(int seq) {
        transactionTemplate.executeWithoutResult(s ->
                awaitRepository.findByWorkflowIdAndSeq(workflowId, seq)
                        .ifPresent(awaitRepository::delete));
    }
}
