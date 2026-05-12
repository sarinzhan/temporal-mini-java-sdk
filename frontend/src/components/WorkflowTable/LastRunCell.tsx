import { useEffect, useState } from 'react';
import { Box, Typography } from '@mui/material';
import { fmtElapsed } from '../../utils/format';
import { RelativeTime } from '../RelativeTime/RelativeTime';
import type { Workflow } from '../../types/workflow';
import type { LastActivity } from '../../types/activity';

interface Props {
  workflow: Workflow;
  /** epoch-ms when the engine started this workflow (from /runtime), or null. */
  runningSince: number | null;
  /** Timestamp of the latest activity attempt, if any. */
  lastAttemptAt: LastActivity['lastAttemptAt'];
}

/**
 * "Last run" column. Three modes:
 *   1. Engine is running this workflow now → live stopwatch ("running 12s").
 *   2. There's a recorded last attempt → relative time ("30s ago").
 *   3. Never run → workflow.startedAt as relative ("5m ago"), or "—".
 */
export function LastRunCell({ workflow, runningSince, lastAttemptAt }: Props) {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    if (runningSince == null) return;
    const id = setInterval(() => setNow(Date.now()), 1_000);
    return () => clearInterval(id);
  }, [runningSince]);

  if (runningSince != null) {
    return (
      <Typography component="span" variant="caption"
                  sx={{ color: 'warning.dark', fontWeight: 600, display: 'inline-flex', alignItems: 'center', gap: 0.75 }}>
        <Box sx={{ width: 6, height: 6, borderRadius: '50%', bgcolor: 'warning.main' }} />
        running {fmtElapsed(now - runningSince)}
      </Typography>
    );
  }
  return <RelativeTime value={lastAttemptAt ?? workflow.startedAt} />;
}
