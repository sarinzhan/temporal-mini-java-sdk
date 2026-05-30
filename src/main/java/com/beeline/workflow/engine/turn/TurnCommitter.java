package com.beeline.workflow.engine.turn;

import com.beeline.workflow.core.model.Event;
import com.beeline.workflow.core.model.Schedule;
import com.beeline.workflow.core.model.TaskStatus;
import com.beeline.workflow.core.model.WorkflowInstance;
import com.beeline.workflow.core.model.WorkflowStatus;
import com.beeline.workflow.engine.replay.EventLogImpl;
import com.beeline.workflow.engine.replay.LockLostException;
import com.beeline.workflow.engine.replay.TaskLease;
import com.beeline.workflow.persistence.repository.EventRepository;
import com.beeline.workflow.persistence.repository.ScheduleRepository;
import com.beeline.workflow.persistence.repository.TaskRepository;
import com.beeline.workflow.persistence.repository.WorkflowRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;

/**
 * Flushes everything produced by one workflow decision turn in a <b>single transaction</b>:
 *
 * <ol>
 *   <li><b>SQL fence</b> — {@code SELECT ... FOR UPDATE} on the task row, asserting our lock_token
 *       still matches. If another node reclaimed the task this returns 0 and we abort the commit
 *       without writing anything (the reclaiming node is authoritative).</li>
 *   <li>Append all buffered events and schedule rows.</li>
 *   <li>Apply the terminal workflow status (RUNNING / COMPLETED / FAILED). The optimistic-lock
 *       {@code @Version} on the workflow row is a second, independent fence against a stale writer.</li>
 *   <li>Finalize the task row (DONE / DEAD), clearing the lock.</li>
 * </ol>
 *
 * Because the whole turn commits atomically, a crash mid-turn leaves <i>no</i> partial history:
 * the task stays PROCESSING, its lease expires, and the turn replays cleanly from the last commit.
 */
public final class TurnCommitter {

    private static final Logger log = LoggerFactory.getLogger(TurnCommitter.class);

    private final EventRepository eventRepository;
    private final ScheduleRepository scheduleRepository;
    private final TaskRepository taskRepository;
    private final WorkflowRepository workflowRepository;
    private final TransactionTemplate tx;

    public TurnCommitter(EventRepository eventRepository,
                         ScheduleRepository scheduleRepository,
                         TaskRepository taskRepository,
                         WorkflowRepository workflowRepository,
                         PlatformTransactionManager transactionManager) {
        this.eventRepository = eventRepository;
        this.scheduleRepository = scheduleRepository;
        this.taskRepository = taskRepository;
        this.workflowRepository = workflowRepository;
        this.tx = new TransactionTemplate(transactionManager);
    }

    /** What terminal state to apply to the workflow row at commit. */
    public record WorkflowMutation(WorkflowStatus status, String resultJson, String error) {
        public static WorkflowMutation running() {
            return new WorkflowMutation(WorkflowStatus.RUNNING, null, null);
        }
        public static WorkflowMutation completed(String resultJson) {
            return new WorkflowMutation(WorkflowStatus.COMPLETED, resultJson, null);
        }
        public static WorkflowMutation failed(String error) {
            return new WorkflowMutation(WorkflowStatus.FAILED, null, error);
        }
    }

    /**
     * Commit the turn. Returns {@code true} if the writes were persisted, {@code false} if the
     * lease was lost (fence failed) and nothing was written.
     *
     * @param taskFinal the task status to finalize with (DONE for completed/parked, DEAD for failed)
     */
    public boolean commit(long workflowId,
                          TaskLease lease,
                          EventLogImpl eventLog,
                          WorkflowMutation mutation,
                          TaskStatus taskFinal) {
        try {
            Boolean ok = tx.execute(status -> {
                // 1. Fence: lock the task row and verify ownership inside this transaction.
                if (lease.taskId() >= 0
                        && taskRepository.lockIfOwned(lease.taskId(), lease.token()) == 0) {
                    return Boolean.FALSE;
                }

                // 2. Events + schedule rows.
                List<Event> events = eventLog.bufferedEvents();
                if (!events.isEmpty()) {
                    eventRepository.saveAll(events);
                }
                List<Schedule> schedules = eventLog.bufferedSchedules();
                if (!schedules.isEmpty()) {
                    scheduleRepository.saveAll(schedules);
                }

                // 3. Workflow status (guarded again by @Version optimistic lock).
                WorkflowInstance wf = workflowRepository.findById(workflowId).orElseThrow(
                        () -> new IllegalStateException("workflow vanished mid-turn: " + workflowId));
                applyMutation(wf, mutation);
                workflowRepository.save(wf);

                // 4. Finalize the task (clear the lock) — fenced by lock_token.
                if (lease.taskId() >= 0) {
                    int updated = taskRepository.finalizeIfOwned(
                            lease.taskId(), lease.token(), taskFinal.name());
                    if (updated == 0) {
                        // Should not happen after lockIfOwned, but be defensive.
                        throw new LockLostException(lease.taskId(), lease.token());
                    }
                }
                return Boolean.TRUE;
            });
            return Boolean.TRUE.equals(ok);
        } catch (LockLostException lost) {
            log.warn("[{}] turn commit aborted — lease lost", workflowId);
            return false;
        } catch (ObjectOptimisticLockingFailureException ole) {
            log.warn("[{}] turn commit aborted — optimistic lock conflict (concurrent writer)", workflowId);
            return false;
        }
    }

    private void applyMutation(WorkflowInstance wf, WorkflowMutation m) {
        wf.setStatus(m.status());
        Instant now = Instant.now();
        wf.setUpdatedAt(now);
        switch (m.status()) {
            case COMPLETED -> {
                wf.setResult(m.resultJson());
                wf.setError(null);
                wf.setCompletedAt(now);
            }
            case FAILED -> {
                wf.setError(m.error());
                wf.setCompletedAt(now);
            }
            default -> { /* RUNNING: status + updatedAt only */ }
        }
    }
}
