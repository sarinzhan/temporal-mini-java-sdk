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
  /** {@code field,dir}, e.g. {@code "createdAt,desc"}. Backend whitelists fields. */
  sort?: string;
}

export const workflowsApi = {
  list: ({ page, size, states, sort }: ListParams) => {
    const q = new URLSearchParams();
    q.set('page', String(page));
    q.set('size', String(size));
    if (sort) q.set('sort', sort);
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
