import { request } from './client';
import type { BucketSize, MetricsHistoryResponse } from '../types/metric';

interface HistoryParams {
  from: string;
  to: string;
  bucket: BucketSize;
}

export const metricsApi = {
  history: ({ from, to, bucket }: HistoryParams) => {
    const q = new URLSearchParams({ from, to, bucket });
    return request<MetricsHistoryResponse>(`/metrics/history?${q}`);
  },
};
