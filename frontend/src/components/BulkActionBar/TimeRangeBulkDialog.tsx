import { useState } from 'react';
import {
  Autocomplete,
  Box,
  Button,
  Chip,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  MenuItem,
  Stack,
  TextField,
} from '@mui/material';
import type { BulkBody } from '../../api/controls';
import type { WorkflowState } from '../../types/workflow';
import { statusColors } from '../../theme';

type Action = 'stop' | 'resume' | 'restart' | 'run-now';

const ACTIONS: { value: Action; label: string }[] = [
  { value: 'stop',    label: 'Stop'    },
  { value: 'resume',  label: 'Resume'  },
  { value: 'restart', label: 'Restart' },
  { value: 'run-now', label: 'Run now' },
];

const STATES: WorkflowState[] = ['NEW', 'RUNNABLE', 'STOPPED', 'FINISHED', 'FAILED'];

interface Props {
  open: boolean;
  onClose: () => void;
  onSubmit: (action: Action, body: BulkBody) => void;
}

/**
 * Dialog for "stop/restart/resume all workflows created between X and Y, optionally
 * filtered by state". Maps onto the same {@code /workflows/bulk/*} endpoints — the
 * backend resolves the {@code {from, to, states}} window to ids before acting.
 */
export function TimeRangeBulkDialog({ open, onClose, onSubmit }: Props) {
  const [action, setAction] = useState<Action>('stop');
  const [from, setFrom] = useState<string>(defaultFrom());
  const [to, setTo]     = useState<string>(defaultTo());
  const [states, setStates] = useState<WorkflowState[]>([]);

  return (
    <Dialog open={open} onClose={onClose} maxWidth="sm" fullWidth>
      <DialogTitle>Bulk action by time range</DialogTitle>
      <DialogContent>
        <Stack spacing={2} sx={{ mt: 1 }}>
          <TextField select label="Action" size="small"
                     value={action} onChange={(e) => setAction(e.target.value as Action)}>
            {ACTIONS.map((a) => <MenuItem key={a.value} value={a.value}>{a.label}</MenuItem>)}
          </TextField>
          <Stack direction="row" spacing={2}>
            <TextField type="datetime-local" label="From" size="small" fullWidth
                       InputLabelProps={{ shrink: true }}
                       value={from} onChange={(e) => setFrom(e.target.value)} />
            <TextField type="datetime-local" label="To" size="small" fullWidth
                       InputLabelProps={{ shrink: true }}
                       value={to} onChange={(e) => setTo(e.target.value)} />
          </Stack>
          <Autocomplete
            multiple
            size="small"
            options={STATES}
            value={states}
            onChange={(_, v) => setStates(v as WorkflowState[])}
            renderTags={(values, getTagProps) =>
              values.map((option, index) => {
                const { key, ...tagProps } = getTagProps({ index });
                return (
                  <Chip
                    key={key}
                    label={option}
                    size="small"
                    sx={{ bgcolor: `${statusColors[option]}22`, color: statusColors[option], fontWeight: 700 }}
                    {...tagProps}
                  />
                );
              })
            }
            renderInput={(params) => (
              <TextField {...params} label="Filter by state (optional)" placeholder="any" />
            )}
          />
          <Box sx={{ fontSize: 12, color: 'text.secondary' }}>
            Window matches the {' '}
            <strong>created_at</strong> field. Workflows whose state forbids the action
            are skipped silently.
          </Box>
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Cancel</Button>
        <Button
          variant="contained"
          color={action === 'restart' ? 'error' : 'primary'}
          onClick={() => onSubmit(action, {
            from: toIsoLocal(from),
            to:   toIsoLocal(to),
            states: states.length > 0 ? states : undefined,
          })}
        >
          {ACTIONS.find((a) => a.value === action)?.label}
        </Button>
      </DialogActions>
    </Dialog>
  );
}

function defaultFrom(): string {
  const d = new Date(Date.now() - 60 * 60_000);
  return toLocalInput(d);
}

function defaultTo(): string {
  return toLocalInput(new Date());
}

function toLocalInput(d: Date): string {
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

function toIsoLocal(local: string): string {
  // The <input type="datetime-local"> value is already in local time but lacks
  // a timezone — Spring's @DateTimeFormat(ISO.DATE_TIME) parses LocalDateTime
  // without zone info, so just append :00 if needed.
  return local.length === 16 ? `${local}:00` : local;
}
