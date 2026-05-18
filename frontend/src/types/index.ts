export type WorkflowStatus = 'PENDING' | 'RUNNING' | 'COMPLETED' | 'FAILED' | 'CANCELLED';

export type EventType =
  | 'WORKFLOW_STARTED'
  | 'WORKFLOW_COMPLETED'
  | 'WORKFLOW_FAILED'
  | 'WORKFLOW_CANCELLED'
  | 'WORKFLOW_RESUMED'
  | 'ACTIVITY_STARTED'
  | 'ACTIVITY_COMPLETED'
  | 'ACTIVITY_FAILED'
  | 'ACTIVITY_RETRYING'
  | 'SIGNAL_SENT';

export interface WorkflowSummary {
  id: string;
  workflowType: string;
  status: WorkflowStatus;
  startTime: string;
  endTime: string | null;
  durationMs: number | null;
}

export interface WorkflowDetail extends WorkflowSummary {
  input: string | null;
  result: string | null;
  error: string | null;
}

export interface WorkflowEvent {
  id: number;
  eventType: EventType;
  activityName: string | null;
  attempt: number | null;
  data: string | null;
  createdAt: string;
}

export interface PendingActivity {
  activityName: string;
  attempt: number;
  maxAttempts: number;
  nextFireAt: string | null;
  lastError: string | null;
  status: string;
}

export interface ActivityOverride {
  activityName: string;
  startToCloseMs: number | null;
  maxAttempts: number | null;
  initialIntervalMs: number | null;
  backoffCoefficient: number | null;
  maxIntervalMs: number | null;
}

export interface PageResponse<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export interface WorkflowSearchParams {
  status?: WorkflowStatus[];
  type?: string;
  id?: string;
  from?: string;
  to?: string;
  quick?: 'all' | 'running' | 'today' | 'last-hour';
  page?: number;
  size?: number;
  sort?: string;
}
