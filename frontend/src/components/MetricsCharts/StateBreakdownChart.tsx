import { useEffect, useMemo, useState } from 'react';
import { Autocomplete, Box, Chip, Paper, TextField, Typography } from '@mui/material';
import { LineChart } from '@mui/x-charts/LineChart';
import type { MetricSample } from '../../types/metric';
import { statusColors } from '../../theme';
import { sampleTime } from './chartUtils';

const STORAGE_KEY = 'temporal-mini.metrics.stateChartStates';

const ALL_STATES = ['NEW', 'RUNNABLE', 'STOPPED', 'FINISHED', 'FAILED'] as const;
type StateKey = typeof ALL_STATES[number];

const FIELDS: Record<StateKey, keyof MetricSample> = {
  NEW: 'cntNew',
  RUNNABLE: 'cntRunnable',
  STOPPED: 'cntBlocked',     // backend column kept the legacy name; same data
  FINISHED: 'cntFinished',
  FAILED: 'cntFailed',
};

interface Props {
  samples: MetricSample[];
  height?: number;
}

/**
 * Workflow counts by state, with an Autocomplete picker that lets the operator
 * add/remove states from the chart. Selection is persisted in localStorage so
 * the next visit keeps the same view.
 *
 * <p>Defaults to {@code RUNNABLE / STOPPED / FAILED} — the actionable signal —
 * and hides {@code NEW / FINISHED} which usually drown out everything else.
 */
export function StateBreakdownChart({ samples, height = 280 }: Props) {
  const [selected, setSelected] = useState<StateKey[]>(() => readInitial());

  useEffect(() => {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(selected));
  }, [selected]);

  const xs = useMemo(() => samples.map(sampleTime), [samples]);

  const series = useMemo(() => selected.map((state) => ({
    data: samples.map((s) => s[FIELDS[state]] as number),
    label: state,
    color: statusColors[state],
    curve: 'monotoneX' as const,
  })), [samples, selected]);

  return (
    <Paper sx={{ p: 2 }}>
      <Box sx={{ display: 'flex', alignItems: 'center', mb: 1.5, gap: 2 }}>
        <Typography variant="subtitle2" sx={{ fontWeight: 700 }}>
          Workflows by state
        </Typography>
        <Autocomplete
          multiple
          size="small"
          options={ALL_STATES.slice()}
          value={selected}
          onChange={(_, v) => setSelected(v as StateKey[])}
          sx={{ flex: 1, maxWidth: 480 }}
          renderTags={(values, getTagProps) =>
            values.map((option, index) => {
              const { key, ...tagProps } = getTagProps({ index });
              return (
                <Chip
                  key={key}
                  label={option}
                  size="small"
                  sx={{ bgcolor: `${statusColors[option as StateKey]}22`, color: statusColors[option as StateKey], fontWeight: 700 }}
                  {...tagProps}
                />
              );
            })
          }
          renderInput={(params) => <TextField {...params} placeholder="States" />}
        />
      </Box>

      {samples.length === 0 || selected.length === 0
        ? <Empty selected={selected.length} />
        : (
          <Box sx={{ width: '100%', height }}>
            <LineChart
              xAxis={[{ data: xs, scaleType: 'time' }]}
              series={series}
              height={height}
              margin={{ left: 50, right: 16, top: 16, bottom: 30 }}
              grid={{ horizontal: true }}
            />
          </Box>
        )}
    </Paper>
  );
}

function Empty({ selected }: { selected: number }) {
  return (
    <Box sx={{ textAlign: 'center', py: 4, color: 'text.disabled' }}>
      <Typography variant="body2">
        {selected === 0 ? 'Pick at least one state' : 'No samples in this window'}
      </Typography>
    </Box>
  );
}

function readInitial(): StateKey[] {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (!raw) return ['RUNNABLE', 'STOPPED', 'FAILED'];
    const parsed = JSON.parse(raw);
    if (Array.isArray(parsed)) {
      return parsed.filter((x): x is StateKey => (ALL_STATES as readonly string[]).includes(x));
    }
  } catch { /* ignore */ }
  return ['RUNNABLE', 'STOPPED', 'FAILED'];
}
