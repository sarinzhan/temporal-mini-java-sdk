import { useQuery } from '@tanstack/react-query';
import { workflowsApi } from '../api/workflows';
import { useRefreshInterval } from './useRefreshInterval';

export function useRuntime() {
  const refetchInterval = useRefreshInterval();
  return useQuery({
    queryKey: ['runtime'],
    queryFn: workflowsApi.runtime,
    refetchInterval,
  });
}
