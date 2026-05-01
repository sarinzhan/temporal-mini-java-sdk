import { request } from './client';
import type {
  Workflow,
  WorkflowPage,
  StatsByState,
  RuntimeMap,
} from '../types/workflow';
import type { Activity, LastActivity } from '../types/activity';

export interface ListParams {
  page: number;
  size: number;
  states: string[];
}

export const workflowsApi = {
  list: ({ page, size, states }: ListParams) => {
    const q = new URLSearchParams();
    q.set('page', String(page));
    q.set('size', String(size));
    states.forEach((s) => q.append('state', s));
    return request<WorkflowPage>(`/workflows?${q}`);
  },
  one: (id: number) => request<Workflow>(`/workflows/${id}`),
  activities: (id: number) => request<Activity[]>(`/workflows/${id}/activities`),
  stats: () => request<StatsByState>('/stats'),
  runtime: () => request<RuntimeMap>('/runtime'),
  lastActivities: (ids: number[]) =>
    ids.length === 0
      ? Promise.resolve({} as Record<number, LastActivity>)
      : request<Record<number, LastActivity>>(
          `/last-activities?ids=${ids.join(',')}`,
        ),
};
