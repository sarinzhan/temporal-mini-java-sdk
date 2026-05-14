export interface RunningTaskDto {
  workflowId: number;
  startedAtEpochMs: number;
}

export interface NodeState {
  nodeId: string;
  nodeUrl: string;
  queueSize: number;
  activeCount: number;
  runningTasks: RunningTaskDto[];
}

export interface AggregatedState {
  nodes: NodeState[];
}
