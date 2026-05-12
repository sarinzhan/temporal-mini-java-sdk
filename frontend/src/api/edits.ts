import { request } from './client';

export type ActivityPayloadField = 'input' | 'output';

export const editsApi = {
  setWorkflowPayload: (id: number, payload: string) =>
    request<{ status: string }>(`/workflows/${id}/payload`, {
      method: 'PUT',
      body: JSON.stringify({ payload }),
    }),
  setActivityPayload: (id: number, activityId: number, field: ActivityPayloadField, payload: string) =>
    request<{ status: string }>(`/workflows/${id}/activities/${activityId}/${field}`, {
      method: 'PUT',
      body: JSON.stringify({ payload }),
    }),
};
