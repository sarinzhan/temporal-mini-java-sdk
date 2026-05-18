import { useState } from 'react';
import { formatTimestamp } from '@/lib/format';
import type { WorkflowEvent } from '@/types';
import { Badge } from '@/components/ui/Badge';
import { eventMeta } from './eventMeta';
import { EventDetailDrawer } from './EventDetailDrawer';

export function EventHistory({ workflowId, events }: { workflowId: string; events: WorkflowEvent[] }) {
  const [selected, setSelected] = useState<WorkflowEvent | null>(null);

  return (
    <div className="overflow-hidden rounded-lg border border-border bg-bg-elevated">
      <table className="w-full text-sm">
        <thead>
          <tr className="border-b border-border bg-bg-subtle text-left text-xs font-medium uppercase tracking-wide text-fg-muted">
            <th className="px-3 py-2 w-16">ID</th>
            <th className="px-3 py-2">Timestamp</th>
            <th className="px-3 py-2">Event</th>
            <th className="px-3 py-2">Details</th>
          </tr>
        </thead>
        <tbody>
          {events.length === 0 && (
            <tr><td colSpan={4} className="py-10 text-center text-fg-muted">No events yet</td></tr>
          )}
          {events.map((ev) => {
            const meta = eventMeta[ev.eventType];
            const Icon = meta.Icon;
            return (
              <tr
                key={ev.id}
                onClick={() => setSelected(ev)}
                className="cursor-pointer border-b border-border last:border-b-0 hover:bg-bg-subtle/60"
              >
                <td className="px-3 py-2 font-mono text-xs text-fg-muted">{ev.id}</td>
                <td className="px-3 py-2 text-xs text-fg-muted">{formatTimestamp(ev.createdAt)}</td>
                <td className="px-3 py-2">
                  <Badge tone={meta.tone}>
                    <Icon className="h-3 w-3" />
                    {meta.label}
                  </Badge>
                </td>
                <td className="px-3 py-2 text-xs text-fg-muted">
                  {ev.activityName && <span className="font-mono text-fg">{ev.activityName}</span>}
                  {ev.attempt != null && <span className="ml-2">attempt {ev.attempt}</span>}
                  {ev.data && (
                    <span className="ml-2 truncate">{(() => {
                      const s = ev.data;
                      return s.length > 80 ? `${s.slice(0, 80)}…` : s;
                    })()}</span>
                  )}
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>

      <EventDetailDrawer
        workflowId={workflowId}
        event={selected}
        onClose={() => setSelected(null)}
      />
    </div>
  );
}
