import { cn } from '@/lib/cn';

type Quick = 'all' | 'running' | 'today' | 'last-hour';

const items: { key: Quick; label: string }[] = [
  { key: 'all', label: 'All' },
  { key: 'running', label: 'Running' },
  { key: 'today', label: 'Today' },
  { key: 'last-hour', label: 'Last hour' },
];

export function QuickFilters({ value, onChange }: { value: Quick; onChange: (q: Quick) => void }) {
  return (
    <div className="inline-flex rounded-md border border-border bg-bg-elevated p-0.5">
      {items.map((it) => (
        <button
          key={it.key}
          onClick={() => onChange(it.key)}
          className={cn(
            'h-7 rounded-sm px-3 text-xs font-medium',
            value === it.key
              ? 'bg-fg text-bg'
              : 'text-fg-muted hover:text-fg hover:bg-bg-subtle',
          )}
        >
          {it.label}
        </button>
      ))}
    </div>
  );
}
