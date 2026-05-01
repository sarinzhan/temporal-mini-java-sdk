import { useRefreshIntervalContext } from '../contexts/RefreshIntervalContext';

/**
 * Returns the value to pass directly to React Query's `refetchInterval`.
 * `false` means "don't auto-refetch"; otherwise it's the millisecond interval.
 */
export function useRefreshInterval(): number | false {
  const { intervalMs } = useRefreshIntervalContext();
  return intervalMs === 0 ? false : intervalMs;
}
