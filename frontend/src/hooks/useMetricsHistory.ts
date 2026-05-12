import { useMemo } from 'react';
import { useQuery } from '@tanstack/react-query';
import { metricsApi } from '../api/metrics';
import { useRefreshInterval } from './useRefreshInterval';
import { findWindow, isoAt } from '../components/MetricsCharts/chartUtils';
import type { WindowKey } from '../types/metric';

/**
 * Fetches history for the given sliding window. The {@code from}/{@code to} pair
 * is computed at query time, but to keep the {@code queryKey} stable across the
 * subsecond drift of {@code Date.now()} we floor {@code now} to the next sample
 * boundary derived from the configured refresh interval.
 */
export function useMetricsHistory(windowKey: WindowKey) {
  const refetchInterval = useRefreshInterval();
  const { from, to, bucket } = useMemo(() => {
    const win = findWindow(windowKey);
    const step = typeof refetchInterval === 'number' ? refetchInterval : 5000;
    const nowMs = Math.floor(Date.now() / step) * step;
    return {
      from: isoAt(nowMs - win.ms),
      to:   isoAt(nowMs),
      bucket: win.bucket,
    };
  }, [windowKey, refetchInterval]);

  return useQuery({
    queryKey: ['metrics-history', windowKey, from, to, bucket],
    queryFn: () => metricsApi.history({ from, to, bucket }),
    refetchInterval,
  });
}
