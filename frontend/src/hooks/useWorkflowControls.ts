import { useMutation, useQueryClient } from '@tanstack/react-query';
import { controlsApi } from '../api/controls';

/**
 * Mutations for workflow control buttons. After any successful mutation we
 * invalidate the lists/details queries so the UI reflects the new state on the
 * next refetch — no manual reload bookkeeping in the components.
 */
export function useWorkflowControls() {
  const qc = useQueryClient();

  const onSuccess = () => {
    qc.invalidateQueries({ queryKey: ['workflows'] });
    qc.invalidateQueries({ queryKey: ['workflow'] });
    qc.invalidateQueries({ queryKey: ['stats'] });
    qc.invalidateQueries({ queryKey: ['runtime'] });
  };

  const runNow  = useMutation({ mutationFn: (id: number) => controlsApi.runNow(id),  onSuccess });
  const block   = useMutation({ mutationFn: (id: number) => controlsApi.block(id),   onSuccess });
  const unblock = useMutation({ mutationFn: (id: number) => controlsApi.unblock(id), onSuccess });

  return { runNow, block, unblock };
}
