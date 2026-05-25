package com.beeline.workflow.engine.replay;

/**
 * Tracks ownership of a single task while a worker thread processes it.
 *
 * <p>A worker claims a task with a unique {@code token}. While processing, the worker
 * periodically renews the lease in the DB (see {@code WorkerLoopImpl.renewLeases}). If a
 * renewal fails because the token no longer matches — meaning the lease expired and another
 * node reclaimed the task — the lease is marked lost and the worker thread is interrupted.
 *
 * <p>Two layers of protection use this:
 * <ul>
 *   <li><b>Cooperative cancel:</b> {@link #markLost()} interrupts the worker thread so it can
 *       stop blocking work (e.g. an activity that respects interrupts) and free its slot.</li>
 *   <li><b>Fencing:</b> {@link #assertOwned()} is called before every state-changing write
 *       (events, workflow status, update results). Once the lease is lost it throws
 *       {@link LockLostException}, so a stale worker can never corrupt state.</li>
 * </ul>
 */
public final class TaskLease {

    /** A lease that is never lost — used for read-only query replay where there is no claim. */
    public static final TaskLease ALWAYS_OWNED = new TaskLease(-1L, "n/a");

    private final long taskId;
    private final String token;
    private volatile boolean lost;
    private volatile Thread workerThread;

    public TaskLease(long taskId, String token) {
        this.taskId = taskId;
        this.token = token;
    }

    public long taskId() {
        return taskId;
    }

    public String token() {
        return token;
    }

    /** Bind the thread currently processing this task, so {@link #markLost()} can interrupt it. */
    public void bindThread(Thread thread) {
        this.workerThread = thread;
    }

    public boolean isLost() {
        return lost;
    }

    /** Mark the lease lost and interrupt the worker thread (best-effort cancel). */
    public void markLost() {
        if (this == ALWAYS_OWNED) return;
        this.lost = true;
        Thread t = workerThread;
        if (t != null) {
            t.interrupt();
        }
    }

    /** Throw {@link LockLostException} if this worker no longer owns the task. */
    public void assertOwned() {
        if (lost) {
            throw new LockLostException(taskId, token);
        }
    }
}
