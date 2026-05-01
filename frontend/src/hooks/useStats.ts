import { useQuery } from '@tanstack/react-query';
import { workflowsApi } from '../api/workflows';
import { useRefreshInterval } from './useRefreshInterval';

export function useStats() {
  const refetchInterval = useRefreshInterval();
  return useQuery({
    queryKey: ['stats'],
    queryFn: workflowsApi.stats,
    refetchInterval,
  });
}
