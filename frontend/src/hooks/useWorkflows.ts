import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { api } from '@/lib/api';
import type {
  ActivityOverride, PageResponse, PendingActivity, WorkflowDetail, WorkflowEvent,
  WorkflowSearchParams, WorkflowSummary,
} from '@/types';

function buildQuery(p: WorkflowSearchParams): string {
  const q = new URLSearchParams();
  if (p.status?.length) p.status.forEach((s) => q.append('status', s));
  if (p.type) q.set('type', p.type);
  if (p.id) q.set('id', p.id);
  if (p.from) q.set('from', p.from);
  if (p.to) q.set('to', p.to);
  if (p.quick) q.set('quick', p.quick);
  if (p.page != null) q.set('page', String(p.page));
  if (p.size != null) q.set('size', String(p.size));
  if (p.sort) q.set('sort', p.sort);
  const s = q.toString();
  return s ? `?${s}` : '';
}

export function useWorkflows(params: WorkflowSearchParams) {
  return useQuery({
    queryKey: ['workflows', params],
    queryFn: () => api.get<PageResponse<WorkflowSummary>>(`/workflows${buildQuery(params)}`),
    refetchInterval: params.quick === 'running' ? 5_000 : 15_000,
  });
}

export function useWorkflowTypes() {
  return useQuery({
    queryKey: ['workflow-types'],
    queryFn: () => api.get<string[]>('/workflows/types'),
    staleTime: 60_000,
  });
}

export function useWorkflow(id: string | undefined) {
  return useQuery({
    queryKey: ['workflow', id],
    queryFn: () => api.get<WorkflowDetail>(`/workflows/${id}`),
    enabled: !!id,
    refetchInterval: (q) => {
      const d = q.state.data as WorkflowDetail | undefined;
      return d && (d.status === 'RUNNING' || d.status === 'PENDING') ? 3_000 : false;
    },
  });
}

export function useWorkflowEvents(id: string | undefined) {
  return useQuery({
    queryKey: ['workflow-events', id],
    queryFn: () => api.get<WorkflowEvent[]>(`/workflows/${id}/events`),
    enabled: !!id,
    refetchInterval: 3_000,
  });
}

export function usePendingActivities(id: string | undefined) {
  return useQuery({
    queryKey: ['workflow-pending', id],
    queryFn: () => api.get<PendingActivity[]>(`/workflows/${id}/pending-activities`),
    enabled: !!id,
    refetchInterval: 3_000,
  });
}

export function useActivityOverride(activityName: string | undefined) {
  return useQuery({
    queryKey: ['activity-override', activityName],
    queryFn: () => api.get<ActivityOverride>(`/activity-overrides/${activityName}`).catch(() => null),
    enabled: !!activityName,
  });
}

export function useSaveActivityOverride() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: ActivityOverride) =>
      api.put<ActivityOverride>(`/activity-overrides/${body.activityName}`, body),
    onSuccess: (_d, vars) => {
      qc.invalidateQueries({ queryKey: ['activity-override', vars.activityName] });
    },
  });
}

export function useWorkflowAction(id: string) {
  const qc = useQueryClient();
  const invalidate = () => {
    qc.invalidateQueries({ queryKey: ['workflow', id] });
    qc.invalidateQueries({ queryKey: ['workflow-events', id] });
    qc.invalidateQueries({ queryKey: ['workflow-pending', id] });
    qc.invalidateQueries({ queryKey: ['workflows'] });
  };
  return {
    cancel: useMutation({
      mutationFn: () => api.post(`/workflows/${id}/cancel`),
      onSuccess: invalidate,
    }),
    resume: useMutation({
      mutationFn: () => api.post(`/workflows/${id}/resume`),
      onSuccess: invalidate,
    }),
    signal: useMutation({
      mutationFn: (body: { signalName: string; payload: string }) =>
        api.post(`/workflows/${id}/signal`, body),
      onSuccess: invalidate,
    }),
    retryActivity: useMutation({
      mutationFn: (activityName: string) =>
        api.post(`/workflows/${id}/activities/${encodeURIComponent(activityName)}/retry`),
      onSuccess: invalidate,
    }),
  };
}
