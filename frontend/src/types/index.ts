export type WorkflowStatus = 'PENDING' | 'RUNNING' | 'COMPLETED' | 'FAILED' | 'CANCELLED';

export type EventType =
  | 'WORKFLOW_CREATED'
  | 'WORKFLOW_TASK_QUEUED'
  | 'WORKFLOW_TASK_STARTED'
  | 'WORKFLOW_TASK_COMPLETED'
  | 'WORKFLOW_COMPLETED'
  | 'WORKFLOW_FAILED'
  | 'WORKFLOW_CANCELLED'
  | 'ACTIVITY_SCHEDULED'
  | 'ACTIVITY_STARTED'
  | 'ACTIVITY_COMPLETED'
  | 'ACTIVITY_FAILED'
  | 'ACTIVITY_RETRY_SCHEDULED'
  | 'TIMER_STARTED'
  | 'TIMER_FIRED'
  | 'AWAIT_BLOCKED'
  | 'AWAIT_FIRED'
  | 'SIGNAL_RECEIVED'
  | 'UPDATE_REQUESTED'
  | 'UPDATE_COMPLETED'
  | 'SIDE_EFFECT_RECORDED'
  | 'VERSION_MARKER';

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
  commandType: string | null;
  seq: number | null;
  activityName: string | null;
  payload: string | null;
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
