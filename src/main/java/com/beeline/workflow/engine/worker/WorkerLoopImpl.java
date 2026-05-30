package com.beeline.workflow.engine.worker;

import com.beeline.workflow.core.model.Task;
import com.beeline.workflow.core.model.TaskStatus;
import com.beeline.workflow.engine.turn.Outcome;
import com.beeline.workflow.engine.turn.WorkflowTurnRunner;
import com.beeline.workflow.engine.replay.TaskLease;
import com.beeline.workflow.persistence.repository.TaskRepository;
import com.beeline.workflow.spring.autoconfigure.WorkflowProperties;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
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
    private final WorkflowTurnRunner turnRunner;
    private final WorkflowProperties properties;
    private final ExecutorService workerPool;
    private final Semaphore slots;
    private final TransactionTemplate transactionTemplate;

    /** Tasks this node is currently processing, by task id — used to renew their leases. */
    private final ConcurrentHashMap<Long, TaskLease> running = new ConcurrentHashMap<>();

    public WorkerLoopImpl(TaskRepository taskRepository,
                          WorkflowTurnRunner turnRunner,
                          WorkflowProperties properties,
                          PlatformTransactionManager transactionManager) {
        this.taskRepository = taskRepository;
        this.turnRunner = turnRunner;
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

        List<Claimed> claimed = claimBatch(available);
        for (Claimed c : claimed) {
            Task t = c.task();
            if (!slots.tryAcquire()) {
                releaseClaim(t.getId());
                continue;
            }
            TaskLease lease = new TaskLease(t.getId(), c.token());
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
        long ttlSeconds = properties.getLockTimeoutSeconds();
        for (TaskLease lease : running.values()) {
            if (lease.isLost()) continue;
            Integer renewed = transactionTemplate.execute(s ->
                    taskRepository.renewLease(lease.taskId(), lease.token(), ttlSeconds));
            if (renewed == null || renewed == 0) {
                log.warn("Lease lost for task {} — another node reclaimed it; cancelling local work",
                        lease.taskId());
                lease.markLost();
            }
        }
    }

    /** A task this node has won, paired with the lock token minted for the claim. */
    private record Claimed(Task task, String token) {}

    private List<Claimed> claimBatch(int batchSize) {
        return transactionTemplate.execute(s -> {
            List<Task> candidates = taskRepository.pollPending(batchSize);
            if (candidates.isEmpty()) return Collections.<Claimed>emptyList();
            long ttlSeconds = properties.getLockTimeoutSeconds();
            String nodeId = properties.getInstanceId();
            List<Claimed> claimed = new ArrayList<>(candidates.size());
            for (Task t : candidates) {
                // pollPending already holds a FOR UPDATE lock on this row inside this tx, so it is still
                // PENDING here. claim() stamps all lock fields from the DB clock; we never mutate (and
                // thus never JPA-flush) the managed entity, so the native version bump cannot collide.
                String token = UUID.randomUUID().toString();
                int updated = taskRepository.claim(t.getId(), nodeId, token, ttlSeconds);
                if (updated == 1) {
                    claimed.add(new Claimed(t, token));
                }
            }
            return claimed;
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
        Outcome outcome;
        try {
            // Every task drives a workflow decision turn; the entry method (and any inline activities)
            // run on this worker thread to completion or until a retry parks the turn. The turn's
            // writes — events, workflow status, AND the task finalization — are committed atomically
            // inside turnRunner.run via TurnCommitter, fenced by our lock token. So for COMPLETED /
            // PARKED / FAILED the task is already finalized here; nothing more to do.
            outcome = turnRunner.run(task, lease);
        } catch (Exception ex) {
            if (lease.isLost()) {
                // Interrupted/aborted because we lost the lock — not a real failure.
                outcome = Outcome.LOST;
            } else {
                log.error("Unexpected error executing workflow task {}", task.getId(), ex);
                outcome = Outcome.FAILED;
            }
        } finally {
            // Stop renewing and detach the thread, so a late renewal can neither extend a finishing
            // task nor interrupt this thread once it moves to the next one.
            running.remove(task.getId());
            lease.bindThread(null);
        }
        finalizeOrphanedTask(lease, outcome);
    }

    /**
     * The turn committer finalizes the task atomically for COMPLETED / PARKED / FAILED, and leaves
     * it alone for LOST. The only case the committer never reaches is UNKNOWN (the workflow row was
     * missing, so no turn ran and nothing was committed) or an unexpected exception escaping the
     * runner — in those we still own the lock and must mark the task DEAD so it isn't reprocessed
     * forever.
     */
    private void finalizeOrphanedTask(TaskLease lease, Outcome outcome) {
        if (outcome != Outcome.UNKNOWN && outcome != Outcome.FAILED) {
            return;  // COMPLETED / PARKED / FAILED-via-commit / LOST: already handled by the committer
        }
        Integer updated = transactionTemplate.execute(s ->
                taskRepository.finalizeIfOwned(lease.taskId(), lease.token(), TaskStatus.DEAD.name()));
        if (updated == null || updated == 0) {
            // Either already finalized by the committer (FAILED) or the lease was lost — both fine.
            log.debug("Task {} orphan-finalize no-op (already finalized or lease lost)", lease.taskId());
        }
    }

    public PoolSnapshot getPoolStats() {
        int max = Math.max(1, properties.getWorkerPoolSize());
        // Concurrency is gated by the semaphore, not the pool size — report the truth: how many
        // slots are in use right now (== tasks this node is actively processing).
        int active = max - slots.availablePermits();
        int queue = 0;
        if (workerPool instanceof ThreadPoolExecutor tpe) {
            queue = tpe.getQueue().size();
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
