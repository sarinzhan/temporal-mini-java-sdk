import { FormControl, InputLabel, MenuItem, Select } from '@mui/material';
import {
  REFRESH_OPTIONS,
  useRefreshIntervalContext,
  type RefreshIntervalMs,
} from '../../contexts/RefreshIntervalContext';

const LABELS: Record<RefreshIntervalMs, string> = {
  0: 'Off',
  2000: '2 sec',
  5000: '5 sec',
  10000: '10 sec',
  30000: '30 sec',
};

export function RefreshIntervalSelect() {
  const { intervalMs, setIntervalMs } = useRefreshIntervalContext();
  return (
    <FormControl size="small" sx={{ minWidth: 130 }}>
      <InputLabel id="refresh-interval-label">Refresh</InputLabel>
      <Select
        labelId="refresh-interval-label"
        label="Refresh"
        value={intervalMs}
        onChange={(e) => setIntervalMs(Number(e.target.value) as RefreshIntervalMs)}
      >
        {REFRESH_OPTIONS.map((v) => (
          <MenuItem key={v} value={v}>{LABELS[v]}</MenuItem>
        ))}
      </Select>
    </FormControl>
  );
}
