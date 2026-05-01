-- "RUNNING" was retired as a persistent state; "currently executing" is now tracked
-- in memory by WorkflowRuntimeRegistry. Persisted state for an active workflow is
-- "RUNNABLE" (queued for the scheduler to pick up).
UPDATE wflow.workflow SET state = 'RUNNABLE' WHERE state = 'RUNNING';
