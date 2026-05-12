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

/**
 * Header dropdown for polling cadence. Styled for the dark AppBar — the default
 * MUI Select uses theme primary text colours which blend into the dark bg.
 */
export function RefreshIntervalSelect() {
  const { intervalMs, setIntervalMs } = useRefreshIntervalContext();
  return (
    <FormControl
      size="small"
      sx={{
        minWidth: 130,
        '& .MuiInputLabel-root':                     { color: 'rgba(255,255,255,.7)' },
        '& .MuiInputLabel-root.Mui-focused':         { color: '#fff' },
        '& .MuiOutlinedInput-root':                  { color: '#fff' },
        '& .MuiOutlinedInput-notchedOutline':        { borderColor: 'rgba(255,255,255,.4)' },
        '& .MuiOutlinedInput-root:hover .MuiOutlinedInput-notchedOutline':        { borderColor: 'rgba(255,255,255,.8)' },
        '& .MuiOutlinedInput-root.Mui-focused .MuiOutlinedInput-notchedOutline':  { borderColor: '#fff' },
        '& .MuiSelect-icon':                         { color: 'rgba(255,255,255,.7)' },
      }}
    >
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
