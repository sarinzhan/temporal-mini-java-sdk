import { useEffect, useState } from 'react';
import { Box, Typography } from '@mui/material';
import { fmtElapsed, toDate } from '../../utils/format';
import type { Workflow } from '../../types/workflow';

interface Props {
  workflow: Workflow;
  /** epoch-ms when the engine started this workflow (from /runtime), or null. */
  runningSince: number | null;
}

/**
 * Renders the right "next run" hint for a row. Has its own 1-second tick so the
 * countdown / running stopwatch updates without re-fetching the list.
 */
export function NextRunCell({ workflow, runningSince }: Props) {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const id = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(id);
  }, []);

  if (runningSince != null) {
    return (
      <Typography variant="caption" sx={{ color: 'warning.dark', fontWeight: 600, display: 'inline-flex', alignItems: 'center', gap: 0.75 }}>
        <Box sx={{ width: 6, height: 6, borderRadius: '50%', bgcolor: 'warning.main' }} />
        running {fmtElapsed(now - runningSince)}
      </Typography>
    );
  }
  if (workflow.state === 'FINISHED' || workflow.state === 'FAILED') {
    return <Typography variant="caption" sx={{ color: 'text.disabled' }}>—</Typography>;
  }
  if (workflow.state === 'BLOCKED') {
    return <Typography variant="caption" sx={{ color: 'text.disabled' }}>paused</Typography>;
  }
  if (!workflow.nextRetryAt) {
    return <Typography variant="caption" sx={{ color: 'info.main', fontWeight: 600 }}>queued</Typography>;
  }
  const target = toDate(workflow.nextRetryAt)?.getTime() ?? null;
  if (target == null) return <Typography variant="caption" sx={{ color: 'text.disabled' }}>—</Typography>;
  const ms = target - now;
  const overdue = ms <= 0;
  return (
    <Typography variant="caption" sx={{ color: overdue ? 'warning.dark' : 'info.main', fontWeight: 600 }}>
      {overdue ? `+${fmtElapsed(-ms)} overdue` : `in ${fmtElapsed(ms)}`}
    </Typography>
  );
}
