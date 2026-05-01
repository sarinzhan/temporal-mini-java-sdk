import { request } from './client';

export const controlsApi = {
  runNow:  (id: number) => request<{ status: string }>(`/workflows/${id}/run-now`, { method: 'POST' }),
  block:   (id: number) => request<{ status: string }>(`/workflows/${id}/block`,   { method: 'POST' }),
  unblock: (id: number) => request<{ status: string }>(`/workflows/${id}/unblock`, { method: 'POST' }),
};
