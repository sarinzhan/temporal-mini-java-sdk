import { Link } from 'react-router-dom';
import { ChevronLeft, ChevronRight, Inbox, Loader2 } from 'lucide-react';
import { Button } from '@/components/ui/Button';
import { StatusBadge } from '@/components/StatusBadge';
import { formatDuration, formatTimestamp, shortId } from '@/lib/format';
import type { PageResponse, WorkflowSummary } from '@/types';

interface WorkflowTableProps {
  data?: PageResponse<WorkflowSummary>;
  loading: boolean;
  page: number;
  size: number;
  onPageChange: (p: number) => void;
}

export function WorkflowTable({ data, loading, page, size, onPageChange }: WorkflowTableProps) {
  const rows = data?.content ?? [];
  const total = data?.totalElements ?? 0;
  const totalPages = data?.totalPages ?? 0;
  const start = total === 0 ? 0 : page * size + 1;
  const end = Math.min((page + 1) * size, total);

  return (
    <div className="overflow-hidden rounded-lg border border-border bg-bg-elevated">
      <div className="overflow-x-auto">
        <table className="w-full text-sm">
          <thead>
            <tr className="border-b border-border bg-bg-subtle text-left text-xs font-medium uppercase tracking-wide text-fg-muted">
              <th className="px-3 py-2 font-medium">Status</th>
              <th className="px-3 py-2 font-medium">Workflow ID</th>
              <th className="px-3 py-2 font-medium">Type</th>
              <th className="px-3 py-2 font-medium">Start</th>
              <th className="px-3 py-2 font-medium">End</th>
              <th className="px-3 py-2 font-medium">Duration</th>
            </tr>
          </thead>
          <tbody>
            {loading && rows.length === 0 ? (
              <tr><td colSpan={6} className="py-12 text-center text-fg-muted">
                <Loader2 className="mx-auto h-5 w-5 animate-spin" />
              </td></tr>
            ) : rows.length === 0 ? (
              <tr><td colSpan={6} className="py-16 text-center text-fg-muted">
                <Inbox className="mx-auto h-6 w-6 mb-1" />
                No workflows found
              </td></tr>
            ) : (
              rows.map((w) => (
                <tr key={w.id} className="border-b border-border last:border-b-0 hover:bg-bg-subtle/60">
                  <td className="px-3 py-2"><StatusBadge status={w.status} /></td>
                  <td className="px-3 py-2 font-mono text-xs">
                    <Link to={`/workflows/${w.id}`} className="text-accent hover:underline">{shortId(w.id, 18)}</Link>
                  </td>
                  <td className="px-3 py-2 text-fg">{w.workflowType}</td>
                  <td className="px-3 py-2 text-xs text-fg-muted">{formatTimestamp(w.startTime)}</td>
                  <td className="px-3 py-2 text-xs text-fg-muted">{formatTimestamp(w.endTime)}</td>
                  <td className="px-3 py-2 font-mono text-xs">{formatDuration(w.durationMs)}</td>
                </tr>
              ))
            )}
          </tbody>
        </table>
      </div>

      <div className="flex items-center justify-between border-t border-border px-3 py-2 text-xs text-fg-muted">
        <div>{total === 0 ? '0 results' : `${start}–${end} of ${total}`}</div>
        <div className="flex items-center gap-1">
          <Button variant="ghost" size="icon" disabled={page <= 0} onClick={() => onPageChange(page - 1)}>
            <ChevronLeft className="h-4 w-4" />
          </Button>
          <span className="px-1 tabular-nums">{page + 1} / {Math.max(1, totalPages)}</span>
          <Button variant="ghost" size="icon" disabled={page + 1 >= totalPages} onClick={() => onPageChange(page + 1)}>
            <ChevronRight className="h-4 w-4" />
          </Button>
        </div>
      </div>
    </div>
  );
}
