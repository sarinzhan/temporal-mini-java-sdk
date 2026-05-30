package com.beeline.workflow.engine.lifecycle;

import com.beeline.workflow.core.model.WorkflowInstance;
import com.beeline.workflow.core.model.WorkflowStatus;
import com.beeline.workflow.engine.codec.PayloadCodec;
import com.beeline.workflow.engine.replay.TaskLease;
import com.beeline.workflow.persistence.repository.WorkflowRepository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;

/**
 * Mutates the {@code wflow.workflows} row for a running workflow: status transitions, result/error.
 * Each call asserts lease ownership before opening its own transaction, so a stale worker cannot
 * overwrite a reclaiming node's state.
 */
public final class WorkflowLifecycleWriter {

    private final WorkflowRepository workflowRepository;
    private final TransactionTemplate transactionTemplate;
    private final PayloadCodec codec;

    public WorkflowLifecycleWriter(WorkflowRepository workflowRepository,
                                   PlatformTransactionManager transactionManager,
                                   PayloadCodec codec) {
        this.workflowRepository = workflowRepository;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.codec = codec;
    }

    public void markRunning(WorkflowInstance wf, TaskLease lease) {
        lease.assertOwned();
        transactionTemplate.executeWithoutResult(s -> {
            wf.setStatus(WorkflowStatus.RUNNING);
            wf.setUpdatedAt(Instant.now());
            workflowRepository.save(wf);
        });
    }

    public void markCompleted(WorkflowInstance wf, Object result, TaskLease lease) {
        lease.assertOwned();
        transactionTemplate.executeWithoutResult(s -> {
            wf.setStatus(WorkflowStatus.COMPLETED);
            wf.setResult(codec.encodeWorkflowValue(result));
            wf.setError(null);
            wf.setCompletedAt(Instant.now());
            wf.setUpdatedAt(Instant.now());
            workflowRepository.save(wf);
        });
    }

    public void markFailed(WorkflowInstance wf, String error, TaskLease lease) {
        lease.assertOwned();
        transactionTemplate.executeWithoutResult(s -> {
            wf.setStatus(WorkflowStatus.FAILED);
            wf.setError(error);
            wf.setUpdatedAt(Instant.now());
            workflowRepository.save(wf);
        });
    }

    /**
     * Used by {@code WorkflowClientImpl.start}: creates the pending workflow row in its own
     * transaction and returns the persisted entity (with its assigned id).
     */
    public WorkflowInstance createPending(String workflowType, String inputJson) {
        return transactionTemplate.execute(s -> {
            WorkflowInstance wf = new WorkflowInstance();
            wf.setWorkflowType(workflowType);
            wf.setStatus(WorkflowStatus.PENDING);
            wf.setInput(inputJson);
            wf.setCreatedAt(Instant.now());
            wf.setUpdatedAt(Instant.now());
            return workflowRepository.save(wf);
        });
    }
}
