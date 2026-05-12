import { useEffect, useState } from 'react';
import { Typography } from '@mui/material';
import { fmtElapsed, toDate } from '../../utils/format';
import type { Workflow } from '../../types/workflow';

interface Props {
  workflow: Workflow;
}

export function NextRunCell({ workflow }: Props) {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const id = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(id);
  }, []);

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
  const overdue = ms <= 0;
  return (
    <Typography variant="caption" sx={{ color: overdue ? 'warning.dark' : 'info.main', fontWeight: 600 }}>
      {overdue ? `+${fmtElapsed(-ms)} overdue` : `in ${fmtElapsed(ms)}`}
    </Typography>
  );
}
