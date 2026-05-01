import { Chip } from '@mui/material';
import type { ColumnDef } from '@tanstack/react-table';
import type { Workflow, RuntimeMap } from '../../types/workflow';
import type { LastActivity } from '../../types/activity';
import { StatusBadge } from '../StatusBadge/StatusBadge';
import { NextRunCell } from './NextRunCell';
import { fmtDate } from '../../utils/format';

/**
 * Column definitions for the workflow list. Keeping the row schema split out
 * here so {@code WorkflowTable} stays a thin shell over TanStack Table.
 */
export function buildWorkflowColumns(
  runtime: RuntimeMap,
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
      cell: (ctx) => {
        const wf = ctx.row.original;
        const isRunning = runtime[wf.id] != null;
        return <StatusBadge state={isRunning ? 'RUNNING' : wf.state} />;
      },
    },
    {
      accessorKey: 'createdAt',
      header: 'Created',
      cell: (ctx) => <span style={{ color: '#6b7280', fontSize: 12 }}>{fmtDate(ctx.getValue() as Workflow['createdAt'])}</span>,
    },
    {
      accessorKey: 'startedAt',
      header: 'Started',
      cell: (ctx) => <span style={{ color: '#6b7280', fontSize: 12 }}>{fmtDate(ctx.getValue() as Workflow['startedAt'])}</span>,
    },
    {
      id: 'nextRun',
      header: 'Next run',
      cell: (ctx) => {
        const wf = ctx.row.original;
        const since = runtime[wf.id] ?? null;
        return <NextRunCell workflow={wf} runningSince={since} />;
      },
    },
    {
      id: 'lastActivity',
      header: 'Current activity',
      cell: (ctx) => {
        const last = lastActs[ctx.row.original.id];
        if (!last) return <span style={{ color: '#9ca3af' }}>—</span>;
        return (
          <span style={{ display: 'inline-flex', gap: 8, alignItems: 'center', fontFamily: 'SFMono-Regular, Consolas, monospace', fontSize: 12, color: '#4b5563' }}>
            {last.name}
            <Chip size="small" label={`#${last.attempt}`} variant="outlined" />
          </span>
        );
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
          }} title={v}>{v}</span>
        );
      },
    },
  ];
}
