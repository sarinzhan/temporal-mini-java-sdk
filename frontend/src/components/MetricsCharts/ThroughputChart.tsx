import { useMemo } from 'react';
import { Box, Paper, Typography } from '@mui/material';
import { BarChart } from '@mui/x-charts/BarChart';
import type { MetricSample } from '../../types/metric';
import { computeDeltas, sampleTime } from './chartUtils';
import { statusColors } from '../../theme';

interface Props {
  samples: MetricSample[];
  height?: number;
}

/**
 * Throughput per bucket. The backend stores cumulative counters; we derive the
 * per-bucket rate by subtracting adjacent samples. The first bucket has no
 * predecessor so its value is {@code null} and the bar is omitted.
 */
export function ThroughputChart({ samples, height = 240 }: Props) {
  const { labels, finished, failed } = useMemo(() => ({
    labels:   samples.map((s) => formatTick(sampleTime(s))),
    finished: computeDeltas(samples, 'cntFinished'),
    failed:   computeDeltas(samples, 'cntFailed'),
  }), [samples]);

  return (
    <Paper sx={{ p: 2 }}>
      <Typography variant="subtitle2" sx={{ mb: 1, fontWeight: 700 }}>
        Throughput (per bucket)
      </Typography>
      {samples.length < 2
        ? <Empty />
        : (
          <Box sx={{ width: '100%', height }}>
            <BarChart
              xAxis={[{ data: labels, scaleType: 'band' }]}
              series={[
                { data: finished, label: 'finished', color: statusColors.FINISHED },
                { data: failed,   label: 'failed',   color: statusColors.FAILED },
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

function formatTick(ms: number): string {
  if (!ms) return '';
  const d = new Date(ms);
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

function Empty() {
  return (
    <Box sx={{ textAlign: 'center', py: 4, color: 'text.disabled' }}>
      <Typography variant="body2">Need at least two samples to compute throughput</Typography>
    </Box>
  );
}
