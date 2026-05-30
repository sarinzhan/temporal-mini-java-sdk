import { Link } from 'react-router-dom';
import { ArrowLeft } from 'lucide-react';
import { StatusBadge } from '@/components/StatusBadge';
import { CopyButton } from '@/components/CopyButton';
import { formatDuration, formatTimestamp } from '@/lib/format';
import { JsonViewer } from '@/components/JsonViewer';
import type { WorkflowDetail } from '@/types';

export function DetailHeader({ workflow, actions }: { workflow: WorkflowDetail; actions: React.ReactNode }) {
  return (
    <div className="space-y-4">
      <div className="flex items-center gap-3">
        <Link
          to="/workflows"
          className="inline-flex h-8 w-8 items-center justify-center rounded-md text-fg-muted hover:bg-bg-subtle hover:text-fg"
        >
          <ArrowLeft className="h-4 w-4" />
        </Link>
        <StatusBadge status={workflow.status} />
        <div className="flex items-center gap-1 font-mono text-sm">
          <span className="text-fg">{workflow.id}</span>
          <CopyButton value={workflow.id} />
        </div>
        <div className="ml-auto">{actions}</div>
      </div>

      <div className="rounded-lg border border-border bg-bg-elevated">
        <div className="grid grid-cols-2 gap-x-6 gap-y-3 border-b border-border px-5 py-4 md:grid-cols-4">
          <Field label="Type">{workflow.workflowType}</Field>
          <Field label="Start">{formatTimestamp(workflow.startTime)}</Field>
          <Field label="End">{formatTimestamp(workflow.endTime)}</Field>
          <Field label="Duration">{formatDuration(workflow.durationMs)}</Field>
        </div>
        <div className="grid gap-4 px-5 py-4 md:grid-cols-2">
          <div>
            <div className="mb-1.5 text-xs font-semibold uppercase tracking-wide text-fg-muted">Input</div>
            <JsonViewer value={workflow.input} />
          </div>
          <div>
            <div className="mb-1.5 text-xs font-semibold uppercase tracking-wide text-fg-muted">Result</div>
            {workflow.error ? (
              <pre className="rounded-md border border-danger/30 bg-danger/5 px-3 py-2 font-mono text-xs text-danger overflow-x-auto whitespace-pre-wrap">{workflow.error}</pre>
            ) : (
              <JsonViewer value={workflow.result} empty="No result yet" />
            )}
          </div>
        </div>
      </div>
    </div>
  );
}

function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div>
      <div className="text-xs font-semibold uppercase tracking-wide text-fg-muted">{label}</div>
      <div className="mt-0.5 text-sm text-fg">{children}</div>
    </div>
  );
}
