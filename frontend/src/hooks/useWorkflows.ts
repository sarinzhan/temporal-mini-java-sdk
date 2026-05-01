import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { workflowsApi, type ListParams } from '../api/workflows';
import { useRefreshInterval } from './useRefreshInterval';

export function useWorkflows(params: ListParams) {
  const refetchInterval = useRefreshInterval();
  return useQuery({
    queryKey: ['workflows', params],
    queryFn: () => workflowsApi.list(params),
    refetchInterval,
    placeholderData: keepPreviousData,
  });
}
