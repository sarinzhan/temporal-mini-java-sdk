import { Badge } from './ui/Badge';
import type { WorkflowStatus } from '@/types';
import { CheckCircle2, CircleX, Loader2, Pause, XCircle, Clock } from 'lucide-react';

const map: Record<WorkflowStatus, { tone: 'success' | 'danger' | 'warn' | 'info' | 'neutral'; label: string; Icon: typeof CheckCircle2 }> = {
  PENDING: { tone: 'neutral', label: 'Pending', Icon: Clock },
  RUNNING: { tone: 'info', label: 'Running', Icon: Loader2 },
  COMPLETED: { tone: 'success', label: 'Completed', Icon: CheckCircle2 },
  FAILED: { tone: 'danger', label: 'Failed', Icon: XCircle },
  CANCELLED: { tone: 'warn', label: 'Cancelled', Icon: Pause },
};

export function StatusBadge({ status }: { status: WorkflowStatus }) {
  const meta = map[status] ?? { tone: 'neutral' as const, label: status, Icon: CircleX };
  const { Icon, tone, label } = meta;
  return (
    <Badge tone={tone}>
      <Icon className={status === 'RUNNING' ? 'h-3 w-3 animate-spin' : 'h-3 w-3'} />
      {label}
    </Badge>
  );
}
