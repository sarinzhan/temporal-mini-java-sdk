import { useEffect, useState } from 'react';
import { Typography } from '@mui/material';
import { fmtElapsed, toDate } from '../../utils/format';
import type { Workflow } from '../../types/workflow';

interface Props {
  workflow: Workflow;
  /** Epoch-ms when the workflow started running on a worker, if it is currently
   *  RUNNING (id present in {@code WorkflowRuntimeRegistry}). Undefined otherwise. */
  runningSince?: number;
}

export function NextRunCell({ workflow, runningSince }: Props) {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const id = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(id);
  }, []);

  if (runningSince != null) {
    return (
      <Typography variant="caption" sx={{ color: 'success.main', fontWeight: 600 }}>
        running for {fmtElapsed(Math.max(0, now - runningSince))}
      </Typography>
    );
  }

  if (workflow.state === 'FINISHED' || workflow.state === 'FAILED') {
    return <Typography variant="caption" sx={{ color: 'text.disabled' }}>—</Typography>;
  }
  if (workflow.state === 'STOPPED') {
    return <Typography variant="caption" sx={{ color: 'text.disabled' }}>paused</Typography>;
  }
  if (!workflow.nextRetryAt) {
    return <Typography variant="caption" sx={{ color: 'info.main', fontWeight: 600 }}>queued</Typography>;
  }
  const target = toDate(workflow.nextRetryAt)?.getTime() ?? null;
  if (target == null) return <Typography variant="caption" sx={{ color: 'text.disabled' }}>—</Typography>;
  const ms = target - now;
  return (
    <Typography variant="caption" sx={{ color: 'info.main', fontWeight: 600 }}>
      {ms <= 0 ? `queued for ${fmtElapsed(-ms)}` : `in ${fmtElapsed(ms)}`}
    </Typography>
  );
}
