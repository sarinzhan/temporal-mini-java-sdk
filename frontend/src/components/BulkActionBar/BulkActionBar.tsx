import { useState } from 'react';
import {
  Alert,
  Box,
  Button,
  Dialog,
  DialogActions,
  DialogContent,
  DialogContentText,
  DialogTitle,
  Paper,
  Stack,
  Typography,
} from '@mui/material';
import PlayArrowIcon from '@mui/icons-material/PlayArrow';
import StopIcon from '@mui/icons-material/Stop';
import ReplayIcon from '@mui/icons-material/Replay';
import RestartAltIcon from '@mui/icons-material/RestartAlt';
import CloseIcon from '@mui/icons-material/Close';
import DateRangeIcon from '@mui/icons-material/DateRange';
import { useWorkflowControls } from '../../hooks/useWorkflowControls';
import type { BulkBody } from '../../api/controls';
import { TimeRangeBulkDialog } from './TimeRangeBulkDialog';

type Action = 'stop' | 'resume' | 'restart' | 'run-now';

interface Props {
  selectedIds: number[];
  onClear: () => void;
}

const ACTIONS: Record<Action, { label: string; verb: string; danger?: boolean; icon: React.ReactNode }> = {
  stop:      { label: 'Stop',     verb: 'stop',    icon: <StopIcon /> },
  resume:    { label: 'Resume',   verb: 'resume',  icon: <ReplayIcon /> },
  restart:   { label: 'Restart',  verb: 'restart', danger: true, icon: <RestartAltIcon /> },
  'run-now': { label: 'Run now',  verb: 'run',     icon: <PlayArrowIcon /> },
};

/**
 * Floats above the workflow table whenever ≥1 row is selected. Bulk actions are
 * dispatched against the explicit id list. The "by time range" button switches
 * to a dialog that builds a {@code {from, to, states}} filter on the backend.
 */
export function BulkActionBar({ selectedIds, onClear }: Props) {
  const { bulkStop, bulkResume, bulkRestart, bulkRunNow } = useWorkflowControls();
  const [confirm, setConfirm] = useState<Action | null>(null);
  const [rangeOpen, setRangeOpen] = useState(false);
  const busy =
    bulkStop.isPending || bulkResume.isPending || bulkRestart.isPending || bulkRunNow.isPending;

  function dispatch(action: Action, body: BulkBody) {
    const onSettled = () => onClear();
    switch (action) {
      case 'stop':    bulkStop.mutate(body, { onSettled }); break;
      case 'resume':  bulkResume.mutate(body, { onSettled }); break;
      case 'restart': bulkRestart.mutate(body, { onSettled }); break;
      case 'run-now': bulkRunNow.mutate(body, { onSettled }); break;
    }
  }

  return (
    <>
      <Paper elevation={3} sx={{
        position: 'sticky', top: 8, zIndex: 5,
        p: 1.25, px: 2,
        bgcolor: 'primary.main', color: 'primary.contrastText',
      }}>
        <Stack direction="row" spacing={1.5} alignItems="center" flexWrap="wrap">
          <Typography variant="body2" sx={{ fontWeight: 700 }}>
            {selectedIds.length} selected
          </Typography>
          <Box sx={{ flex: 1 }} />
          {(['run-now', 'stop', 'resume', 'restart'] as Action[]).map((a) => (
            <Button
              key={a}
              size="small"
              variant="contained"
              color={ACTIONS[a].danger ? 'error' : 'inherit'}
              startIcon={ACTIONS[a].icon}
              disabled={busy}
              onClick={() => setConfirm(a)}
              sx={ACTIONS[a].danger ? {} : { color: 'primary.main', bgcolor: 'background.paper' }}
            >
              {ACTIONS[a].label}
            </Button>
          ))}
          <Button size="small" variant="outlined" color="inherit"
                  startIcon={<DateRangeIcon />} onClick={() => setRangeOpen(true)} disabled={busy}>
            By time range…
          </Button>
          <Button size="small" color="inherit" startIcon={<CloseIcon />} onClick={onClear}>
            Clear
          </Button>
        </Stack>
      </Paper>

      <Dialog open={confirm !== null} onClose={() => setConfirm(null)}>
        <DialogTitle>
          {confirm && `${ACTIONS[confirm].label} ${selectedIds.length} workflow(s)?`}
        </DialogTitle>
        <DialogContent>
          <DialogContentText sx={{ mb: 1 }}>
            {confirm === 'restart'
              ? 'Each workflow will have its activity history wiped and re-run from scratch. This cannot be undone.'
              : `The engine will ${confirm ? ACTIONS[confirm].verb : ''} every selected workflow that allows the transition. Others are skipped silently.`}
          </DialogContentText>
          {confirm === 'restart' && (
            <Alert severity="warning" variant="outlined">Destructive operation.</Alert>
          )}
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setConfirm(null)}>Cancel</Button>
          <Button
            variant="contained"
            color={confirm === 'restart' ? 'error' : 'primary'}
            onClick={() => {
              if (confirm) dispatch(confirm, { ids: selectedIds });
              setConfirm(null);
            }}
          >
            {confirm && ACTIONS[confirm].label}
          </Button>
        </DialogActions>
      </Dialog>

      <TimeRangeBulkDialog
        open={rangeOpen}
        onClose={() => setRangeOpen(false)}
        onSubmit={(action, body) => { dispatch(action, body); setRangeOpen(false); }}
      />
    </>
  );
}
