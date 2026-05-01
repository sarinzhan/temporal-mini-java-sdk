import { useQuery } from '@tanstack/react-query';
import { workflowsApi } from '../api/workflows';
import { useRefreshInterval } from './useRefreshInterval';

export function useWorkflow(id: number) {
  const refetchInterval = useRefreshInterval();
  return useQuery({
    queryKey: ['workflow', id],
    queryFn: () => workflowsApi.one(id),
    refetchInterval,
  });
}
