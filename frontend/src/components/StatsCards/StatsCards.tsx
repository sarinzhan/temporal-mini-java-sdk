import type { MouseEvent } from 'react';
import { Box } from '@mui/material';
import { StatCard } from './StatCard';
import type { StatsByState, VisualWorkflowState } from '../../types/workflow';

const ORDER: VisualWorkflowState[] = ['NEW', 'IN_QUEUE', 'WAITING', 'STOPPED', 'FINISHED', 'FAILED'];

const LABELS: Partial<Record<VisualWorkflowState, string>> = {
  IN_QUEUE: 'In Queue',
  WAITING:  'Waiting',
};

interface Props {
  stats?: StatsByState;
  /** Selected filter values (state strings passed directly to the API). Empty = ALL. */
  selected: string[];
  onChange: (next: string[]) => void;
}

/**
 * Multi-select filter chips. Plain click replaces the selection; Cmd/Ctrl-click
 * toggles. Clicking the "All" tile clears the filter — same UX as the legacy UI.
 * IN_QUEUE and WAITING are passed as-is to the API — the backend resolves them.
 */
export function StatsCards({ stats, selected, onChange }: Props) {
  const dbTotal = ORDER.reduce((sum, k) => sum + (stats?.[k] ?? 0), 0);

  function handleClick(state: string, e: MouseEvent) {
    if (state === 'ALL') {
      onChange([]);
      return;
    }
    const multi = e.metaKey || e.ctrlKey;
    if (multi) {
      onChange(selected.includes(state)
        ? selected.filter((s) => s !== state)
        : [...selected, state]);
    } else {
      onChange([state]);
    }
  }

  return (
    <Box sx={{ display: 'grid', gridTemplateColumns: 'repeat(7, 1fr)', gap: 1.5 }}>
      <StatCard
        label="All"
        value={dbTotal || '—'}
        state="ALL"
        active={selected.length === 0}
        onClick={(e) => handleClick('ALL', e)}
      />
      {ORDER.map((s) => (
        <StatCard
          key={s}
          label={LABELS[s] ?? (s.charAt(0) + s.slice(1).toLowerCase())}
          value={stats?.[s] ?? '—'}
          state={s}
          active={selected.includes(s)}
          onClick={(e) => handleClick(s, e)}
        />
      ))}
    </Box>
  );
}
