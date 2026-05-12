import { Chip } from '@mui/material';
import type { ColumnDef } from '@tanstack/react-table';
import type { Workflow } from '../../types/workflow';
import type { LastActivity } from '../../types/activity';
import { StatusBadge } from '../StatusBadge/StatusBadge';
import { NextRunCell } from './NextRunCell';
import { RelativeTime } from '../RelativeTime/RelativeTime';
import { LastRunCell } from './LastRunCell';

/**
 * Backend allow-list (mirrors {@code WorkflowUiController.SORTABLE_FIELDS}).
 * Headers in this set become clickable {@code <TableSortLabel>}s; the rest are
 * plain labels. Keeping the column ids identical to backend field names lets
 * us pass the sort state through unchanged.
 */
export const SORTABLE_COLUMNS = new Set(['id', 'createdAt', 'state']);

/**
 * Column definitions for the workflow list. Time fields render as auto-updating
 * relative timestamps; absolute values are shown in tooltips on hover.
 */
export function buildWorkflowColumns(
  lastActs: Record<number, LastActivity>,
): ColumnDef<Workflow>[] {
  return [
    {
      accessorKey: 'id',
      header: 'ID',
      cell: (ctx) => <span style={{ fontWeight: 600, color: '#6c8eff' }}>#{ctx.getValue<number>()}</span>,
    },
    {
      accessorKey: 'workflowType',
      header: 'Type',
      cell: (ctx) => <span style={{ fontFamily: 'SFMono-Regular, Consolas, monospace' }}>{ctx.getValue<string>()}</span>,
    },
    {
      accessorKey: 'state',
      header: 'State',
      cell: (ctx) => <StatusBadge state={ctx.row.original.state} />,
    },
    {
      accessorKey: 'createdAt',
      header: 'Created',
      cell: (ctx) => <RelativeTime value={ctx.getValue() as Workflow['createdAt']} />,
    },
    {
      id: 'lastRun',
      header: 'Last run',
      cell: (ctx) => {
        const wf = ctx.row.original;
        const last = lastActs[wf.id];
        return (
          <LastRunCell
            workflow={wf}
            lastAttemptAt={last?.lastAttemptAt ?? null}
          />
        );
      },
    },
    {
      id: 'nextRun',
      header: 'Next run',
      cell: (ctx) => <NextRunCell workflow={ctx.row.original} />,
    },
    {
      id: 'lastActivity',
      header: 'Activity',
      cell: (ctx) => {
        const last = lastActs[ctx.row.original.id];
        if (!last) return <span style={{ color: '#9ca3af' }}>—</span>;
        return (
          <span style={{
            fontFamily: 'SFMono-Regular, Consolas, monospace',
            fontSize: 12,
            color: '#4b5563',
          }}>
            {last.name}
          </span>
        );
      },
    },
    {
      id: 'attempts',
      header: 'Attempts',
      cell: (ctx) => {
        const last = lastActs[ctx.row.original.id];
        if (!last) return <span style={{ color: '#9ca3af' }}>—</span>;
        return <Chip size="small" label={`#${last.attempt}`} variant="outlined" />;
      },
    },
    {
      accessorKey: 'errorMessage',
      header: 'Error',
      cell: (ctx) => {
        const v = ctx.getValue() as string | null;
        if (!v) return null;
        return (
          <span style={{
            color: '#dc2626',
            display: 'inline-block',
            maxWidth: 240,
            overflow: 'hidden',
            textOverflow: 'ellipsis',
            whiteSpace: 'nowrap',
            fontSize: 12,
          }} title={v}>{v}</span>
        );
      },
    },
  ];
}
