import type { MetricSample, WindowDef, WindowKey } from '../../types/metric';
import { toDate } from '../../utils/format';

const MIN = 60_000;
const HOUR = 60 * MIN;
const DAY = 24 * HOUR;

/**
 * Catalog of windows available in the picker. Bucket size is chosen so that
 * windowMs / bucketMs ≤ ~300 — that's the upper bound of points we want to
 * render per chart for legibility and payload size.
 */
export const WINDOWS: WindowDef[] = [
  { key: '5m',  label: '5m',  ms: 5 * MIN,  bucket: 'raw' },
  { key: '30m', label: '30m', ms: 30 * MIN, bucket: 'second' },
  { key: '1h',  label: '1h',  ms: HOUR,     bucket: 'second' },
  { key: '6h',  label: '6h',  ms: 6 * HOUR, bucket: 'minute' },
  { key: '24h', label: '24h', ms: DAY,      bucket: 'minute' },
  { key: '7d',  label: '7d',  ms: 7 * DAY,  bucket: 'hour'   },
  { key: '14d', label: '14d', ms: 14 * DAY, bucket: 'hour'   },
];

export function findWindow(key: WindowKey): WindowDef {
  return WINDOWS.find((w) => w.key === key) ?? WINDOWS[2];
}

/** ISO string for a given epoch-ms (for the API). */
export function isoAt(ms: number): string {
  return new Date(ms).toISOString().replace(/Z$/, '');
}

export function sampleTime(s: MetricSample): number {
  return toDate(s.ts)?.getTime() ?? 0;
}

/**
 * Compute throughput per bucket as the delta of cumulative counters between
 * adjacent samples. The first sample has no predecessor, so it gets `null`
 * and the chart skips it. Same for any decreasing delta (which would only
 * happen if retention drops the previous bucket — we render that gap).
 */
export function computeDeltas(samples: MetricSample[], field: 'cntFinished' | 'cntFailed'): (number | null)[] {
  const out: (number | null)[] = new Array(samples.length).fill(null);
  for (let i = 1; i < samples.length; i++) {
    const d = samples[i][field] - samples[i - 1][field];
    out[i] = d >= 0 ? d : null;
  }
  return out;
}
