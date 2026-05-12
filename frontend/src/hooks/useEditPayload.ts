import { useMutation, useQueryClient } from '@tanstack/react-query';
import { editsApi, type ActivityPayloadField } from '../api/edits';
import { toastBus } from '../utils/toastBus';

/**
 * Mutations for editing the workflow input payload and per-activity input/output
 * payloads. After success we invalidate workflow + activities so the UI shows
 * the new value without a manual refresh.
 */
export function useEditPayload(workflowId: number) {
  const qc = useQueryClient();
  const onSuccess = () => {
    qc.invalidateQueries({ queryKey: ['workflow', workflowId] });
    qc.invalidateQueries({ queryKey: ['activities', workflowId] });
    qc.invalidateQueries({ queryKey: ['workflows'] });
    toastBus.push('Payload saved', 'success');
  };

  const setWorkflowPayload = useMutation({
    mutationFn: (payload: string) => editsApi.setWorkflowPayload(workflowId, payload),
    onSuccess,
  });

  const setActivityPayload = useMutation({
    mutationFn: ({ activityId, field, payload }: { activityId: number; field: ActivityPayloadField; payload: string }) =>
      editsApi.setActivityPayload(workflowId, activityId, field, payload),
    onSuccess,
  });

  return { setWorkflowPayload, setActivityPayload };
}
