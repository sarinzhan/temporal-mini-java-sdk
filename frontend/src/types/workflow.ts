/**
 * Persisted lifecycle states. "RUNNING" is intentionally NOT persisted — it's a
 * runtime view served separately via /runtime, but it's a useful display state.
 */
export type DbWorkflowState =
  | 'NEW'
  | 'RUNNABLE'
  | 'BLOCKED'
  | 'FINISHED'
  | 'FAILED';

export type VisualWorkflowState = DbWorkflowState | 'RUNNING';

export interface Workflow {
  id: number;
  workflowType: string;
  state: DbWorkflowState;
  createdAt: string | number[] | null;
  startedAt: string | number[] | null;
  nextRetryAt: string | number[] | null;
  errorMessage: string | null;
  nextPayload: string | null;
}

export interface WorkflowPage {
  content: Workflow[];
  number: number;
  totalPages: number;
  totalElements: number;
}

export type StatsByState = Partial<Record<VisualWorkflowState, number>>;

/** id → epoch-ms when the workflow was submitted to the executor. */
export type RuntimeMap = Record<number, number>;
