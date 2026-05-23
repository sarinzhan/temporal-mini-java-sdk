package com.beeline.workflow.engine.update;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory map of updateId → {@code CompletableFuture<UpdateResult>}. The HTTP request handler
 * awaits the future; the worker completes it after writing UPDATE_COMPLETED. Surviving JVM
 * restarts requires re-reading the update_requests table on startup — see {@link UpdateRecovery}.
 */
public class UpdateRegistry {

    public record UpdateResult(boolean success, Object value, String error) {}

    private final Map<String, CompletableFuture<UpdateResult>> futures = new ConcurrentHashMap<>();

    public CompletableFuture<UpdateResult> registerPending(String updateId) {
        CompletableFuture<UpdateResult> f = new CompletableFuture<>();
        futures.put(updateId, f);
        return f;
    }

    public void completeSuccess(String updateId, Object value) {
        CompletableFuture<UpdateResult> f = futures.remove(updateId);
        if (f != null) f.complete(new UpdateResult(true, value, null));
    }

    public void completeFailure(String updateId, String error) {
        CompletableFuture<UpdateResult> f = futures.remove(updateId);
        if (f != null) f.complete(new UpdateResult(false, null, error));
    }
}
