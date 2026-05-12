import { request } from './client';
import type { WorkflowState } from '../types/workflow';

export interface BulkBody {
  ids?: number[];
  from?: string;
  to?: string;
  states?: WorkflowState[];
}

export interface BulkResponse {
  affected: number;
}

export const controlsApi = {
  runNow:  (id: number) => request<{ status: string }>(`/workflows/${id}/run-now`, { method: 'POST' }),
  stop:    (id: number) => request<{ status: string }>(`/workflows/${id}/stop`,    { method: 'POST' }),
  resume:  (id: number) => request<{ status: string }>(`/workflows/${id}/resume`,  { method: 'POST' }),
  restart: (id: number) => request<{ status: string }>(`/workflows/${id}/restart`, { method: 'POST' }),
  restartFromActivity: (id: number, activityId: number) =>
    request<{ status: string }>(`/workflows/${id}/restart-from-activity`, {
      method: 'POST',
      body: JSON.stringify({ activityId }),
    }),

  bulkRunNow:  (body: BulkBody) => request<BulkResponse>('/workflows/bulk/run-now', { method: 'POST', body: JSON.stringify(body) }),
  bulkStop:    (body: BulkBody) => request<BulkResponse>('/workflows/bulk/stop',    { method: 'POST', body: JSON.stringify(body) }),
  bulkResume:  (body: BulkBody) => request<BulkResponse>('/workflows/bulk/resume',  { method: 'POST', body: JSON.stringify(body) }),
  bulkRestart: (body: BulkBody) => request<BulkResponse>('/workflows/bulk/restart', { method: 'POST', body: JSON.stringify(body) }),
};
