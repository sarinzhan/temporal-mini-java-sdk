import { useMutation, useQueryClient } from '@tanstack/react-query';
import { controlsApi, type BulkBody } from '../api/controls';
import { toastBus } from '../utils/toastBus';

/**
 * Mutations for workflow control buttons. Every successful mutation invalidates
 * the lists/details/runtime queries so the UI reflects the new state without
 * manual reload bookkeeping in the components.
 */
export function useWorkflowControls() {
  const qc = useQueryClient();

  const onSuccess = () => {
    qc.invalidateQueries({ queryKey: ['workflows'] });
    qc.invalidateQueries({ queryKey: ['workflow'] });
    qc.invalidateQueries({ queryKey: ['activities'] });
    qc.invalidateQueries({ queryKey: ['stats'] });
    qc.invalidateQueries({ queryKey: ['runtime'] });
    qc.invalidateQueries({ queryKey: ['last-activities'] });
  };

  const runNow  = useMutation({ mutationFn: (id: number) => controlsApi.runNow(id),  onSuccess });
  const stop    = useMutation({ mutationFn: (id: number) => controlsApi.stop(id),    onSuccess });
  const resume  = useMutation({ mutationFn: (id: number) => controlsApi.resume(id),  onSuccess });
  const restart = useMutation({ mutationFn: (id: number) => controlsApi.restart(id), onSuccess });
  const restartFromActivity = useMutation({
    mutationFn: ({ id, activityId }: { id: number; activityId: number }) =>
      controlsApi.restartFromActivity(id, activityId),
    onSuccess,
  });

  const bulkRunNow  = useMutation({
    mutationFn: (body: BulkBody) => controlsApi.bulkRunNow(body),
    onSuccess: (res) => { toastBus.push(`Run-now applied to ${res.affected} workflow(s)`, 'success'); onSuccess(); },
  });
  const bulkStop    = useMutation({
    mutationFn: (body: BulkBody) => controlsApi.bulkStop(body),
    onSuccess: (res) => { toastBus.push(`Stopped ${res.affected} workflow(s)`,        'success'); onSuccess(); },
  });
  const bulkResume  = useMutation({
    mutationFn: (body: BulkBody) => controlsApi.bulkResume(body),
    onSuccess: (res) => { toastBus.push(`Resumed ${res.affected} workflow(s)`,        'success'); onSuccess(); },
  });
  const bulkRestart = useMutation({
    mutationFn: (body: BulkBody) => controlsApi.bulkRestart(body),
    onSuccess: (res) => { toastBus.push(`Restarted ${res.affected} workflow(s)`,      'success'); onSuccess(); },
  });

  return {
    runNow, stop, resume, restart, restartFromActivity,
    bulkRunNow, bulkStop, bulkResume, bulkRestart,
  };
}
