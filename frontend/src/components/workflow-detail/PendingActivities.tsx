import { AlertCircle, Clock, RefreshCw } from 'lucide-react';
import { Badge } from '@/components/ui/Badge';
import { Button } from '@/components/ui/Button';
import { formatTimestamp } from '@/lib/format';
import { useWorkflowAction } from '@/hooks/useWorkflows';
import type { PendingActivity } from '@/types';

export function PendingActivities({ workflowId, items }: { workflowId: string; items: PendingActivity[] }) {
  const { retryActivity } = useWorkflowAction(workflowId);

  if (items.length === 0) {
    return (
      <div className="rounded-lg border border-border bg-bg-elevated p-8 text-center text-sm text-fg-muted">
        No pending activities.
      </div>
    );
  }

  return (
    <div className="space-y-2">
      {items.map((a) => {
        const dead = a.status === 'DEAD';
        return (
          <div
            key={`${a.activityName}-${a.attempt}`}
            className="rounded-lg border border-border bg-bg-elevated p-4"
          >
            <div className="flex items-start justify-between gap-4">
              <div className="min-w-0 flex-1">
                <div className="flex items-center gap-2">
                  <span className="font-mono text-sm text-fg">{a.activityName}</span>
                  <Badge tone={dead ? 'danger' : 'warn'}>
                    {dead ? <AlertCircle className="h-3 w-3" /> : <RefreshCw className="h-3 w-3" />}
                    {a.status}
                  </Badge>
                  <span className="text-xs text-fg-muted">
                    attempt {a.attempt}/{a.maxAttempts}
                  </span>
                </div>
                {a.nextFireAt && (
                  <div className="mt-1.5 flex items-center gap-1.5 text-xs text-fg-muted">
                    <Clock className="h-3 w-3" />
                    Next attempt: {formatTimestamp(a.nextFireAt)}
                  </div>
                )}
                {a.lastError && (
                  <pre className="mt-2 rounded-md border border-danger/30 bg-danger/5 px-2.5 py-1.5 font-mono text-xs text-danger overflow-x-auto whitespace-pre-wrap">
                    {a.lastError.split('\n').slice(0, 4).join('\n')}
                  </pre>
                )}
              </div>
              {dead && (
                <Button
                  variant="secondary"
                  size="sm"
                  onClick={() => retryActivity.mutate(a.activityName)}
                  disabled={retryActivity.isPending}
                >
                  Force retry
                </Button>
              )}
            </div>
          </div>
        );
      })}
    </div>
  );
}
