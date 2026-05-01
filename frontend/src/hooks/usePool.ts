import { useQuery } from '@tanstack/react-query';
import { poolApi } from '../api/pool';
import { useRefreshInterval } from './useRefreshInterval';

export function usePool() {
  const refetchInterval = useRefreshInterval();
  return useQuery({
    queryKey: ['pool'],
    queryFn: poolApi.get,
    refetchInterval,
  });
}
