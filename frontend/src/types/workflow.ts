/** Persisted lifecycle states — match the backend WorkflowState enum exactly. */
export type DbWorkflowState =
  | 'NEW'
  | 'RETRY'
  | 'STOPPED'
  | 'FINISHED'
  | 'FAILED';

/**
 * Visual states shown in the UI.
 * - {@code IN_QUEUE} and {@code WAITING} are derived server-side from RETRY rows
 *   split by {@code nextRetryAt} (labeled "Ready to run" and "Waiting retry").
 * - {@code RUNNING} is derived from the in-memory runtime registry (worker thread
 *   actually executing). None of these are persisted enum values.
 */
export type VisualWorkflowState = DbWorkflowState | 'IN_QUEUE' | 'WAITING' | 'RUNNING';

/** Alias for ergonomics — matches the persisted enum on the backend. */
export type WorkflowState = DbWorkflowState;

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
