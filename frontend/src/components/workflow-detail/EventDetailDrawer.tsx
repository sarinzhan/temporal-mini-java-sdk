import { useEffect, useState } from 'react';
import { Save } from 'lucide-react';
import { Dialog, DialogContent } from '@/components/ui/Dialog';
import { Button } from '@/components/ui/Button';
import { Input } from '@/components/ui/Input';
import { Badge } from '@/components/ui/Badge';
import { JsonViewer } from '@/components/JsonViewer';
import { formatTimestamp } from '@/lib/format';
import { eventMeta } from './eventMeta';
import {
  useActivityOverride, useSaveActivityOverride, useWorkflowAction,
} from '@/hooks/useWorkflows';
import type { ActivityOverride, WorkflowEvent } from '@/types';

interface Props {
  workflowId: string;
  event: WorkflowEvent | null;
  onClose: () => void;
}

export function EventDetailDrawer({ workflowId, event, onClose }: Props) {
  const open = !!event;
  return (
    <Dialog open={open} onOpenChange={(o) => { if (!o) onClose(); }}>
      {event && <DialogContent side="right" title={<EventTitle event={event} />} description={formatTimestamp(event.createdAt)}>
        <EventBody workflowId={workflowId} event={event} />
      </DialogContent>}
    </Dialog>
  );
}

function EventTitle({ event }: { event: WorkflowEvent }) {
  const m = eventMeta[event.eventType];
  const Icon = m.Icon;
  return (
    <span className="inline-flex items-center gap-2">
      <Badge tone={m.tone}><Icon className="h-3 w-3" />{m.label}</Badge>
      {event.activityName && <span className="font-mono text-xs text-fg-muted">{event.activityName}</span>}
    </span>
  );
}

function EventBody({ workflowId, event }: { workflowId: string; event: WorkflowEvent }) {
  const isActivity = event.eventType.startsWith('ACTIVITY_');
  return (
    <div className="space-y-4 p-5">
      <KeyValue label="Event ID" value={String(event.id)} />
      <KeyValue label="Timestamp" value={formatTimestamp(event.createdAt)} />
      {event.activityName && <KeyValue label="Activity" value={event.activityName} mono />}
      {event.seq != null && <KeyValue label="Seq" value={String(event.seq)} />}
      {event.commandType && <KeyValue label="Command" value={event.commandType} />}

      <div>
        <div className="mb-1.5 text-xs font-semibold uppercase tracking-wide text-fg-muted">Payload</div>
        {event.eventType === 'ACTIVITY_FAILED' || event.eventType === 'ACTIVITY_RETRY_SCHEDULED' || event.eventType === 'WORKFLOW_FAILED' ? (
          <FailureView raw={event.payload} />
        ) : (
          <JsonViewer value={event.payload} empty="—" />
        )}
      </div>

      {isActivity && event.activityName && (
        <ActivityOverrideEditor activityName={event.activityName} />
      )}

      {event.eventType === 'ACTIVITY_FAILED' && event.activityName && (
        <RetrySection workflowId={workflowId} activityName={event.activityName} />
      )}
    </div>
  );
}

function KeyValue({ label, value, mono }: { label: string; value: string; mono?: boolean }) {
  return (
    <div className="flex items-baseline gap-3">
      <div className="w-28 shrink-0 text-xs font-semibold uppercase tracking-wide text-fg-muted">{label}</div>
      <div className={`text-sm text-fg ${mono ? 'font-mono' : ''}`}>{value}</div>
    </div>
  );
}

function FailureView({ raw }: { raw: string | null }) {
  if (!raw) return <span className="text-fg-faint italic">—</span>;
  const lines = raw.split('\n').slice(0, 20);
  return (
    <pre className="rounded-md border border-danger/30 bg-danger/5 px-3 py-2 font-mono text-xs text-danger overflow-x-auto whitespace-pre-wrap">
      {lines.join('\n')}
      {raw.split('\n').length > 20 && '\n…'}
    </pre>
  );
}

function ActivityOverrideEditor({ activityName }: { activityName: string }) {
  const { data: existing } = useActivityOverride(activityName);
  const save = useSaveActivityOverride();
  const [draft, setDraft] = useState<ActivityOverride>({
    activityName,
    startToCloseMs: null, maxAttempts: null,
    initialIntervalMs: null, backoffCoefficient: null, maxIntervalMs: null,
  });

  useEffect(() => {
    if (existing) {
      setDraft({
        activityName,
        startToCloseMs: existing.startToCloseMs ?? null,
        maxAttempts: existing.maxAttempts ?? null,
        initialIntervalMs: existing.initialIntervalMs ?? null,
        backoffCoefficient: existing.backoffCoefficient ?? null,
        maxIntervalMs: existing.maxIntervalMs ?? null,
      });
    }
  }, [existing, activityName]);

  const submit = () => save.mutate(draft);
  const setNum = (k: keyof ActivityOverride, v: string) => {
    const n = v === '' ? null : Number(v);
    setDraft({ ...draft, [k]: Number.isNaN(n as number) ? null : n });
  };

  return (
    <div className="rounded-md border border-border bg-bg-subtle p-3">
      <div className="mb-2 flex items-center justify-between">
        <div className="text-xs font-semibold uppercase tracking-wide text-fg-muted">Runtime overrides</div>
        {existing && <Badge tone="accent">override active</Badge>}
      </div>
      <div className="grid grid-cols-2 gap-2">
        <NumField label="Start-to-close (ms)" value={draft.startToCloseMs} onChange={(v) => setNum('startToCloseMs', v)} />
        <NumField label="Max attempts" value={draft.maxAttempts} onChange={(v) => setNum('maxAttempts', v)} />
        <NumField label="Initial interval (ms)" value={draft.initialIntervalMs} onChange={(v) => setNum('initialIntervalMs', v)} />
        <NumField label="Backoff coefficient" value={draft.backoffCoefficient} onChange={(v) => setNum('backoffCoefficient', v)} step="0.1" />
        <NumField label="Max interval (ms)" value={draft.maxIntervalMs} onChange={(v) => setNum('maxIntervalMs', v)} />
      </div>
      <div className="mt-3 flex justify-end gap-2">
        <Button variant="primary" size="sm" onClick={submit} disabled={save.isPending}>
          <Save className="h-3.5 w-3.5" />
          Save
        </Button>
      </div>
      {save.isSuccess && <div className="mt-2 text-xs text-success">Saved. Applied on next attempt.</div>}
      {save.isError && <div className="mt-2 text-xs text-danger">Failed to save.</div>}
    </div>
  );
}

function NumField({ label, value, onChange, step }: {
  label: string; value: number | null; onChange: (v: string) => void; step?: string;
}) {
  return (
    <label className="block">
      <div className="mb-1 text-[11px] font-medium text-fg-muted">{label}</div>
      <Input
        type="number"
        step={step ?? '1'}
        value={value ?? ''}
        onChange={(e) => onChange(e.target.value)}
        placeholder="—"
      />
    </label>
  );
}

function RetrySection({ workflowId, activityName }: { workflowId: string; activityName: string }) {
  const { retryActivity } = useWorkflowAction(workflowId);
  return (
    <div className="rounded-md border border-warn/30 bg-warn/5 p-3">
      <div className="text-xs font-semibold uppercase tracking-wide text-warn">Stuck activity</div>
      <p className="mt-1 text-xs text-fg-muted">Reset this activity and re-enqueue the workflow.</p>
      <Button
        variant="secondary"
        size="sm"
        className="mt-2"
        onClick={() => retryActivity.mutate(activityName)}
        disabled={retryActivity.isPending}
      >
        Force retry now
      </Button>
    </div>
  );
}
