import { Filter } from 'lucide-react';
import { useMemo, useState } from 'react';
import { Button } from '@/components/ui/Button';
import { Input } from '@/components/ui/Input';
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/Popover';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/Select';
import type { WorkflowStatus } from '@/types';
import { useWorkflowTypes } from '@/hooks/useWorkflows';

export interface AdvancedFilterState {
  statuses: WorkflowStatus[];
  type?: string;
  id?: string;
  from?: string;
  to?: string;
}

const STATUSES: WorkflowStatus[] = ['PENDING', 'RUNNING', 'COMPLETED', 'FAILED', 'CANCELLED'];

function toInputValue(iso?: string): string {
  if (!iso) return '';
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return '';
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

function fromInputValue(v: string): string | undefined {
  if (!v) return undefined;
  const d = new Date(v);
  if (Number.isNaN(d.getTime())) return undefined;
  return d.toISOString();
}

export function AdvancedFilters({
  value,
  onChange,
}: {
  value: AdvancedFilterState;
  onChange: (next: AdvancedFilterState) => void;
}) {
  const { data: types } = useWorkflowTypes();
  const [draft, setDraft] = useState(value);
  const activeCount = useMemo(() => {
    let n = 0;
    if (value.statuses.length) n += 1;
    if (value.type) n += 1;
    if (value.id) n += 1;
    if (value.from || value.to) n += 1;
    return n;
  }, [value]);

  const toggleStatus = (s: WorkflowStatus) => {
    const set = new Set(draft.statuses);
    set.has(s) ? set.delete(s) : set.add(s);
    setDraft({ ...draft, statuses: Array.from(set) });
  };

  const apply = () => onChange(draft);
  const reset = () => {
    const empty: AdvancedFilterState = { statuses: [] };
    setDraft(empty);
    onChange(empty);
  };

  return (
    <Popover onOpenChange={(o) => { if (o) setDraft(value); }}>
      <PopoverTrigger asChild>
        <Button variant="secondary" size="md">
          <Filter className="h-3.5 w-3.5" />
          Filters
          {activeCount > 0 && (
            <span className="ml-1 rounded-full bg-fg px-1.5 text-[10px] font-semibold text-bg">{activeCount}</span>
          )}
        </Button>
      </PopoverTrigger>
      <PopoverContent align="start" className="w-[420px]">
        <div className="space-y-3">
          <div>
            <div className="mb-1.5 text-xs font-semibold text-fg-muted">Status</div>
            <div className="flex flex-wrap gap-1.5">
              {STATUSES.map((s) => {
                const active = draft.statuses.includes(s);
                return (
                  <button
                    key={s}
                    onClick={() => toggleStatus(s)}
                    className={`h-7 rounded-md border px-2 text-xs font-medium ${
                      active
                        ? 'border-fg bg-fg text-bg'
                        : 'border-border bg-bg-elevated text-fg-muted hover:text-fg'
                    }`}
                  >
                    {s}
                  </button>
                );
              })}
            </div>
          </div>

          <div>
            <div className="mb-1.5 text-xs font-semibold text-fg-muted">Workflow ID</div>
            <Input
              value={draft.id ?? ''}
              onChange={(e) => setDraft({ ...draft, id: e.target.value })}
              placeholder="UUID or substring"
            />
          </div>

          <div>
            <div className="mb-1.5 text-xs font-semibold text-fg-muted">Workflow type</div>
            <Select
              value={draft.type ?? ''}
              onValueChange={(v) => setDraft({ ...draft, type: v === '__any__' ? undefined : v })}
            >
              <SelectTrigger><SelectValue placeholder="Any type" /></SelectTrigger>
              <SelectContent>
                <SelectItem value="__any__">Any type</SelectItem>
                {(types ?? []).map((t) => <SelectItem key={t} value={t}>{t}</SelectItem>)}
              </SelectContent>
            </Select>
          </div>

          <div className="grid grid-cols-2 gap-2">
            <div>
              <div className="mb-1.5 text-xs font-semibold text-fg-muted">Start from</div>
              <input
                type="datetime-local"
                value={toInputValue(draft.from)}
                onChange={(e) => setDraft({ ...draft, from: fromInputValue(e.target.value) })}
                className="h-8 w-full rounded-md border border-border bg-bg-elevated px-2.5 text-sm text-fg"
              />
            </div>
            <div>
              <div className="mb-1.5 text-xs font-semibold text-fg-muted">Start to</div>
              <input
                type="datetime-local"
                value={toInputValue(draft.to)}
                onChange={(e) => setDraft({ ...draft, to: fromInputValue(e.target.value) })}
                className="h-8 w-full rounded-md border border-border bg-bg-elevated px-2.5 text-sm text-fg"
              />
            </div>
          </div>

          <div className="flex justify-between border-t border-border pt-3">
            <Button variant="ghost" size="sm" onClick={reset}>Reset</Button>
            <Button variant="primary" size="sm" onClick={apply}>Apply</Button>
          </div>
        </div>
      </PopoverContent>
    </Popover>
  );
}
