package com.beeline.workflow.engine.worker;

import com.beeline.workflow.core.model.Task;
import com.beeline.workflow.core.model.TaskStatus;
import com.beeline.workflow.engine.executor.WorkflowExecutor;
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
            try {
                workerPool.submit(() -> {
                    try {
                        processTask(t);
                    } finally {
                        slots.release();
                    }
                });
            } catch (RejectedExecutionException rex) {
                slots.release();
                releaseClaim(t.getId());
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
            taskRepository.save(fresh);
        });
    }

    private void processTask(Task task) {
        WorkflowExecutor.Outcome outcome;
        try {
            outcome = workflowExecutor.execute(task);
        } catch (Exception ex) {
            log.error("Unexpected error executing workflow task {}", task.getId(), ex);
            outcome = WorkflowExecutor.Outcome.FAILED;
        }
        finalizeTask(task.getId(), outcome);
    }

    private void finalizeTask(Long taskId, WorkflowExecutor.Outcome outcome) {
        transactionTemplate.executeWithoutResult(s -> {
            Task fresh = taskRepository.findById(taskId).orElse(null);
            if (fresh == null) return;
            switch (outcome) {
                case COMPLETED, RETRYING -> fresh.setStatus(TaskStatus.DONE);
                case FAILED, UNKNOWN -> fresh.setStatus(TaskStatus.DEAD);
            }
            fresh.setLockedBy(null);
            fresh.setLockedAt(null);
            fresh.setLockedUntil(null);
            taskRepository.save(fresh);
        });
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
