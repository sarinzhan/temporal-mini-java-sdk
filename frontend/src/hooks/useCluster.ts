import { useQuery } from '@tanstack/react-query';
import { clusterApi } from '../api/cluster';
import { useRefreshInterval } from './useRefreshInterval';

export function useCluster() {
  const refetchInterval = useRefreshInterval();
  return useQuery({
    queryKey: ['cluster'],
    queryFn: clusterApi.getState,
    refetchInterval,
  });
}
