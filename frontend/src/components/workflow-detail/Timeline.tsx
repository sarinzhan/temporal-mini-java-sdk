import { useMemo, useState } from 'react';
import { ZoomIn, ZoomOut } from 'lucide-react';
import { Button } from '@/components/ui/Button';
import { Tooltip } from '@/components/ui/Tooltip';
import { formatDuration, formatTimestamp } from '@/lib/format';
import type { WorkflowEvent } from '@/types';

interface Bar {
  name: string;
  start: number;
  end: number | null;
  status: 'running' | 'completed' | 'failed' | 'retrying';
  attempt: number;
}

export function Timeline({ events }: { events: WorkflowEvent[] }) {
  const [zoom, setZoom] = useState(1);

  const { bars, t0, t1 } = useMemo(() => buildBars(events), [events]);
  const total = Math.max(1, t1 - t0);

  if (bars.length === 0) {
    return (
      <div className="rounded-lg border border-border bg-bg-elevated p-8 text-center text-sm text-fg-muted">
        Timeline appears once activities start running.
      </div>
    );
  }

  const rowKeys = Array.from(new Set(bars.map((b) => b.name)));
  const widthBase = 100; // %
  const widthScaled = widthBase * zoom;

  return (
    <div className="overflow-hidden rounded-lg border border-border bg-bg-elevated">
      <div className="flex items-center justify-between border-b border-border px-4 py-2">
        <div className="text-xs text-fg-muted">
          {formatTimestamp(new Date(t0).toISOString())} → {formatTimestamp(new Date(t1).toISOString())}
        </div>
        <div className="flex items-center gap-1">
          <Button variant="ghost" size="icon" onClick={() => setZoom((z) => Math.max(1, z - 0.5))}><ZoomOut className="h-4 w-4" /></Button>
          <span className="px-1 text-xs tabular-nums text-fg-muted">{zoom.toFixed(1)}x</span>
          <Button variant="ghost" size="icon" onClick={() => setZoom((z) => Math.min(8, z + 0.5))}><ZoomIn className="h-4 w-4" /></Button>
        </div>
      </div>

      <div className="overflow-x-auto">
        <div style={{ width: `${widthScaled}%`, minWidth: '100%' }}>
          {rowKeys.map((name) => (
            <div key={name} className="flex items-center border-b border-border last:border-b-0">
              <div className="sticky left-0 z-10 w-44 shrink-0 truncate border-r border-border bg-bg-elevated px-3 py-2 font-mono text-xs text-fg">
                {name}
              </div>
              <div className="relative h-9 flex-1 bg-bg-subtle/40">
                {bars.filter((b) => b.name === name).map((b, i) => {
                  const left = ((b.start - t0) / total) * 100;
                  const right = (((b.end ?? t1) - t0) / total) * 100;
                  const width = Math.max(0.5, right - left);
                  return (
                    <Tooltip
                      key={i}
                      content={
                        <div className="space-y-0.5">
                          <div className="font-mono text-xs">{b.name}</div>
                          <div className="text-xs text-fg-muted">attempt {b.attempt} · {b.status}</div>
                          <div className="text-xs text-fg-muted">{formatDuration((b.end ?? Date.now()) - b.start)}</div>
                        </div>
                      }
                    >
                      <div
                        className={`absolute top-1.5 h-6 rounded-sm border ${barColor(b.status)}`}
                        style={{ left: `${left}%`, width: `${width}%` }}
                      />
                    </Tooltip>
                  );
                })}
              </div>
            </div>
          ))}
        </div>
      </div>
    </div>
  );
}

function barColor(status: Bar['status']): string {
  switch (status) {
    case 'running':   return 'border-info/40 bg-info/30';
    case 'completed': return 'border-success/40 bg-success/30';
    case 'failed':    return 'border-danger/40 bg-danger/30';
    case 'retrying':  return 'border-warn/40 bg-warn/30';
  }
}

function buildBars(events: WorkflowEvent[]): { bars: Bar[]; t0: number; t1: number } {
  if (events.length === 0) return { bars: [], t0: 0, t1: 1 };

  const starts = new Map<string, { start: number; attempt: number }>();
  const bars: Bar[] = [];

  let t0 = Number.POSITIVE_INFINITY;
  let t1 = Number.NEGATIVE_INFINITY;

  for (const e of events) {
    const ts = new Date(e.createdAt).getTime();
    t0 = Math.min(t0, ts);
    t1 = Math.max(t1, ts);

    if (!e.activityName) continue;
    const key = `${e.activityName}#${e.attempt ?? 0}`;

    if (e.eventType === 'ACTIVITY_STARTED') {
      starts.set(key, { start: ts, attempt: e.attempt ?? 0 });
    } else if (e.eventType === 'ACTIVITY_COMPLETED' || e.eventType === 'ACTIVITY_FAILED' || e.eventType === 'ACTIVITY_RETRYING') {
      const s = starts.get(key);
      if (s) {
        bars.push({
          name: e.activityName,
          start: s.start,
          end: ts,
          attempt: s.attempt,
          status: e.eventType === 'ACTIVITY_COMPLETED' ? 'completed'
                : e.eventType === 'ACTIVITY_RETRYING' ? 'retrying'
                : 'failed',
        });
        starts.delete(key);
      }
    }
  }

  // any still running
  for (const [key, s] of starts.entries()) {
    const name = key.split('#')[0];
    bars.push({ name, start: s.start, end: null, attempt: s.attempt, status: 'running' });
  }

  if (!Number.isFinite(t0)) t0 = Date.now();
  if (!Number.isFinite(t1) || t1 <= t0) t1 = t0 + 1000;
  // pad 5% on each side
  const pad = (t1 - t0) * 0.05;
  t0 = t0 - pad;
  t1 = t1 + pad;

  return { bars, t0, t1 };
}
