import { useMemo } from 'react';
import { Box, Paper, Typography } from '@mui/material';
import { LineChart } from '@mui/x-charts/LineChart';
import type { MetricSample } from '../../types/metric';
import { sampleTime } from './chartUtils';

interface Props {
  samples: MetricSample[];
  height?: number;
}

/**
 * Free-worker count and queue depth over time. Emphasises the operator's
 * questions: "are we maxing out the pool" (free → 0) and "is work piling up"
 * (queue > 0).
 */
export function PoolQueueChart({ samples, height = 260 }: Props) {
  const { xs, free, queue } = useMemo(() => {
    return {
      xs:    samples.map(sampleTime),
      free:  samples.map((s) => s.poolFree),
      queue: samples.map((s) => s.poolQueue),
    };
  }, [samples]);

  return (
    <Paper sx={{ p: 2 }}>
      <Typography variant="subtitle2" sx={{ mb: 1, fontWeight: 700 }}>
        Pool & queue
      </Typography>
      {samples.length === 0
        ? <Empty />
        : (
          <Box sx={{ width: '100%', height }}>
            <LineChart
              xAxis={[{ data: xs, scaleType: 'time' }]}
              series={[
                { data: free,  label: 'pool free',  color: '#3ecf8e', curve: 'monotoneX' },
                { data: queue, label: 'queue',      color: '#f59e0b', curve: 'monotoneX' },
              ]}
              height={height}
              margin={{ left: 50, right: 16, top: 16, bottom: 30 }}
              grid={{ horizontal: true }}
            />
          </Box>
        )}
    </Paper>
  );
}

function Empty() {
  return (
    <Box sx={{ textAlign: 'center', py: 4, color: 'text.disabled' }}>
      <Typography variant="body2">No samples in this window</Typography>
    </Box>
  );
}
