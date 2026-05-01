import { useQuery } from '@tanstack/react-query';
import { workflowsApi } from '../api/workflows';
import { useRefreshInterval } from './useRefreshInterval';

export function useActivities(workflowId: number) {
  const refetchInterval = useRefreshInterval();
  return useQuery({
    queryKey: ['activities', workflowId],
    queryFn: () => workflowsApi.activities(workflowId),
    refetchInterval,
  });
}
