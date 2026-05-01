import { useQuery } from '@tanstack/react-query';
import { workflowsApi } from '../api/workflows';
import { useRefreshInterval } from './useRefreshInterval';

export function useLastActivities(ids: number[]) {
  const refetchInterval = useRefreshInterval();
  // Sort the id list so the queryKey is stable when callers pass ids in different orders.
  const stable = [...ids].sort((a, b) => a - b);
  return useQuery({
    queryKey: ['last-activities', stable],
    queryFn: () => workflowsApi.lastActivities(stable),
    enabled: stable.length > 0,
    refetchInterval,
  });
}
