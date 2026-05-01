import { Box, LinearProgress, Paper, Skeleton, Stack, Typography } from '@mui/material';
import { usePool } from '../../hooks/usePool';

export function PoolGauge() {
  const { data, isLoading } = usePool();

  if (isLoading || !data) {
    return (
      <Paper sx={{ p: 2 }}>
        <Skeleton variant="text" width="40%" />
        <Skeleton variant="rectangular" height={6} sx={{ mt: 1 }} />
      </Paper>
    );
  }

  const activePct = data.maxPoolSize > 0 ? (data.active / data.maxPoolSize) * 100 : 0;
  const queuePct  = data.queueCapacity > 0 ? (data.queue / data.queueCapacity) * 100 : 0;

  return (
    <Paper sx={{ p: 2 }}>
      <Stack direction="row" spacing={4} alignItems="center" flexWrap="wrap">
        <Stat label="Workers"
              value={`${data.active} active`}
              hint={`${data.free} free / ${data.maxPoolSize} max`}
              progress={activePct}
              color="warning" />
        <Stat label="Queue"
              value={`${data.queue} waiting`}
              hint={`${data.queueCapacity} capacity`}
              progress={queuePct}
              color="primary" />
      </Stack>
    </Paper>
  );
}

function Stat({
  label, value, hint, progress, color,
}: {
  label: string;
  value: string;
  hint: string;
  progress: number;
  color: 'primary' | 'warning';
}) {
  return (
    <Box sx={{ minWidth: 220, flex: 1 }}>
      <Stack direction="row" justifyContent="space-between" alignItems="baseline">
        <Typography variant="caption" sx={{ textTransform: 'uppercase', fontWeight: 600, color: 'text.secondary' }}>
          {label}
        </Typography>
        <Typography variant="caption" sx={{ color: 'text.disabled' }}>{hint}</Typography>
      </Stack>
      <Typography variant="body1" sx={{ fontWeight: 600 }}>{value}</Typography>
      <LinearProgress variant="determinate" value={Math.min(100, progress)} color={color} sx={{ mt: 0.5, height: 6, borderRadius: 1 }} />
    </Box>
  );
}
