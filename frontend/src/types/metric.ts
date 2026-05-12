/** One row from /metrics/history. Either a raw sample or a bucket aggregate. */
export interface MetricSample {
  ts: string | number[];
  poolActive: number;
  poolFree: number;
  poolQueue: number;
  runtimeCount: number;
  cntNew: number;
  cntRunnable: number;
  cntBlocked: number;
  cntFinished: number;
  cntFailed: number;
}

export interface MetricsHistoryResponse {
  bucket: BucketSize;
  count: number;
  samples: MetricSample[];
}

export type BucketSize = 'raw' | 'second' | 'minute' | 'hour' | 'day';

export type WindowKey = '5m' | '30m' | '1h' | '6h' | '24h' | '7d' | '14d';

export interface WindowDef {
  key: WindowKey;
  label: string;
  /** Window size in milliseconds. */
  ms: number;
  /** Bucket the backend should aggregate by — keeps response under ~300 points. */
  bucket: BucketSize;
}
