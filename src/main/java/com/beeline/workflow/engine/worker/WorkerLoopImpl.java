package com.beeline.workflow.engine.worker;

import com.beeline.workflow.core.model.Task;
import com.beeline.workflow.core.model.TaskStatus;
import com.beeline.workflow.engine.executor.WorkflowExecutor;
import com.beeline.workflow.engine.replay.TaskLease;
import com.beeline.workflow.persistence.repository.TaskRepository;
import com.beeline.workflow.spring.autoconfigure.WorkflowProperties;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class WorkerLoopImpl implements WorkerLoop {

    private static final Logger log = LoggerFactory.getLogger(WorkerLoopImpl.class);

    private final TaskRepository taskRepository;
    private final WorkflowExecutor workflowExecutor;
    private final WorkflowProperties properties;
    private final ExecutorService workerPool;
    private final Semaphore slots;
    private final TransactionTemplate transactionTemplate;

    /** Tasks this node is currently processing, by task id — used to renew their leases. */
    private final ConcurrentHashMap<Long, TaskLease> running = new ConcurrentHashMap<>();

    public WorkerLoopImpl(TaskRepository taskRepository,
                          WorkflowExecutor workflowExecutor,
                          WorkflowProperties properties,
                          PlatformTransactionManager transactionManager) {
        this.taskRepository = taskRepository;
        this.workflowExecutor = workflowExecutor;
        this.properties = properties;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        int pool = Math.max(1, properties.getWorkerPoolSize());
        this.slots = new Semaphore(pool);
        AtomicInteger counter = new AtomicInteger();
        ThreadFactory tf = r -> {
            Thread t = new Thread(r, "wf-worker-" + counter.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
        this.workerPool = Executors.newFixedThreadPool(pool, tf);
    }

    @Override
    @Scheduled(fixedDelayString = "${workflow.poll-interval-ms:1000}")
    public void pollAndProcess() {
        int available = slots.availablePermits();
        if (available <= 0) return;

        List<Task> claimed = claimBatch(available);
        for (Task t : claimed) {
            if (!slots.tryAcquire()) {
                releaseClaim(t.getId());
                continue;
            }
            TaskLease lease = new TaskLease(t.getId(), t.getLockToken());
            running.put(t.getId(), lease);
            try {
                workerPool.submit(() -> {
                    lease.bindThread(Thread.currentThread());
                    try {
                        processTask(t, lease);
                    } finally {
                        running.remove(t.getId());
                        slots.release();
                        // Clear any pending interrupt before the pooled thread is reused.
                        Thread.interrupted();
                    }
                });
            } catch (RejectedExecutionException rex) {
                running.remove(t.getId());
                lease.bindThread(null);
                slots.release();
                releaseClaim(t.getId());
            }
        }
    }

    /**
     * Periodically extend the lease of every task this node is still processing. As long as the
     * node is alive this keeps {@code locked_until} in the future, so a slow-but-healthy turn is
     * never reclaimed by the TimeoutWatcher. If a renewal returns 0 rows, another node already
     * reclaimed the task — we mark the lease lost (which interrupts the worker thread and fences
     * any further writes).
     */
    @Scheduled(fixedDelayString = "${workflow.lease-renew-interval-ms:20000}")
    public void renewLeases() {
        if (running.isEmpty()) return;
        Instant until = Instant.now().plus(properties.getLockTimeoutSeconds(), ChronoUnit.SECONDS);
        for (TaskLease lease : running.values()) {
            if (lease.isLost()) continue;
            Integer renewed = transactionTemplate.execute(s ->
                    taskRepository.renewLease(lease.taskId(), lease.token(), until));
            if (renewed == null || renewed == 0) {
                log.warn("Lease lost for task {} — another node reclaimed it; cancelling local work",
                        lease.taskId());
                lease.markLost();
            }
        }
    }

private List<Task> claimBatch(int batchSize) {
        return transactionTemplate.execute(s -> {
            List<Task> candidates = taskRepository.pollPending(batchSize);
            if (candidates.isEmpty()) return Collections.<Task>emptyList();
            Instant now = Instant.now();
            Instant lockUntil = now.plus(properties.getLockTimeoutSeconds(), ChronoUnit.SECONDS);
            for (Task t : candidates) {
                t.setStatus(TaskStatus.PROCESSING);
                t.setLockedBy(properties.getInstanceId());
                t.setLockedAt(now);
                t.setLockedUntil(lockUntil);
                t.setLockToken(UUID.randomUUID().toString());
            }
            taskRepository.saveAll(candidates);
            return candidates;
        });
    }

    private void releaseClaim(Long taskId) {
        transactionTemplate.executeWithoutResult(s -> {
            Task fresh = taskRepository.findById(taskId).orElse(null);
            if (fresh == null) return;
            fresh.setStatus(TaskStatus.PENDING);
            fresh.setLockedBy(null);
            fresh.setLockedAt(null);
            fresh.setLockedUntil(null);
            fresh.setLockToken(null);
            taskRepository.save(fresh);
        });
    }

    private void processTask(Task task, TaskLease lease) {
        WorkflowExecutor.Outcome outcome;
        try {
            // Every task drives a workflow decision turn; the entry method (and any inline activities)
            // run on this worker thread to completion or until a retry parks the turn.
            outcome = workflowExecutor.execute(task, lease);
        } catch (Exception ex) {
            if (lease.isLost()) {
                // Interrupted/aborted because we lost the lock — not a real failure.
                outcome = WorkflowExecutor.Outcome.LOST;
            } else {
                log.error("Unexpected error executing workflow task {}", task.getId(), ex);
                outcome = WorkflowExecutor.Outcome.FAILED;
            }
        } finally {
            // Stop renewing and detaching the thread BEFORE we finalize, so a late renewal can
            // neither extend a finishing task nor interrupt this thread once it moves to the next one.
            running.remove(task.getId());
            lease.bindThread(null);
        }
        finalizeTask(lease, outcome);
    }

    private void finalizeTask(TaskLease lease, WorkflowExecutor.Outcome outcome) {
        if (outcome == WorkflowExecutor.Outcome.LOST) {
            log.warn("Task {} finalize skipped — lease lost, the reclaiming node owns it now", lease.taskId());
            return;
        }
        TaskStatus status = switch (outcome) {
            case COMPLETED, PARKED -> TaskStatus.DONE;
            case FAILED, UNKNOWN -> TaskStatus.DEAD;
            case LOST -> null;  // unreachable, handled above
        };
        // Fenced finalize: only writes if we still own the lock. Returns 0 if the lease was lost
        // mid-turn, in which case we must not overwrite the new owner's state.
        Integer updated = transactionTemplate.execute(s ->
                taskRepository.finalizeIfOwned(lease.taskId(), lease.token(), status.name()));
        if (updated == null || updated == 0) {
            log.warn("Task {} finalize skipped — lease lost to another node", lease.taskId());
        }
    }

    public PoolSnapshot getPoolStats() {
        int max = Math.max(1, properties.getWorkerPoolSize());
        int active = max;
        int queue = 0;
        if (workerPool instanceof ThreadPoolExecutor tpe) {
            active = tpe.getActiveCount();
            queue = tpe.getQueue().size();
            max = tpe.getMaximumPoolSize();
        }
        return new PoolSnapshot(active, queue, max);
    }

    public record PoolSnapshot(int active, int queue, int max) {}

    @PreDestroy
    public void shutdown() {
        workerPool.shutdown();
        try {
            if (!workerPool.awaitTermination(30, TimeUnit.SECONDS)) {
                workerPool.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            workerPool.shutdownNow();
        }
    }
}
