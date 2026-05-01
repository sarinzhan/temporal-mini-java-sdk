export interface Activity {
  id: number;
  workflowId: number;
  name: string;
  attempt: number;
  success: boolean;
  startedAt: string | number[] | null;
  finishedAt: string | number[] | null;
  inputPayload: string | null;
  outputPayload: string | null;
  errorMessage: string | null;
}

export interface LastActivity {
  name: string;
  attempt: number;
}
