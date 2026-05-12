import { useState } from 'react';
import {
  Button,
  Dialog,
  DialogActions,
  DialogContent,
  DialogContentText,
  DialogTitle,
  Stack,
} from '@mui/material';
import PlayArrowIcon from '@mui/icons-material/PlayArrow';
import StopIcon from '@mui/icons-material/Stop';
import ReplayIcon from '@mui/icons-material/Replay';
import RestartAltIcon from '@mui/icons-material/RestartAlt';
import { useWorkflowControls } from '../../hooks/useWorkflowControls';
import type { Workflow } from '../../types/workflow';

interface Props {
  workflow: Workflow;
}

/**
 * Run-now / Stop / Resume / Restart buttons. Visibility mirrors the engine's
 * allowed transitions ({@code WorkflowEngine.RUN_NOW_FORBIDDEN / STOPPABLE / RESUMABLE}).
 * Restart is allowed in any state (it's the "I want to start over" hammer) but
 * always confirms because it wipes activity history.
 */
export function WorkflowControls({ workflow }: Props) {
  const { runNow, stop, resume, restart } = useWorkflowControls();
  const [confirmRestart, setConfirmRestart] = useState(false);

  const id = workflow.id;
  const busy =
    runNow.isPending || stop.isPending || resume.isPending || restart.isPending;

  return (
    <>
      <Stack direction="row" spacing={1} flexWrap="wrap">
        {workflow.state !== 'FINISHED' && (
          <Button size="small" variant="contained" color="success"
                  startIcon={<PlayArrowIcon />} disabled={busy}
                  onClick={() => runNow.mutate(id)}>
            Run now
          </Button>
        )}
        {(workflow.state === 'NEW' || workflow.state === 'RUNNABLE') && (
          <Button size="small" variant="contained" color="warning"
                  startIcon={<StopIcon />} disabled={busy}
                  onClick={() => stop.mutate(id)}>
            Stop
          </Button>
        )}
        {(workflow.state === 'STOPPED' || workflow.state === 'FAILED') && (
          <Button size="small" variant="contained" color="secondary"
                  startIcon={<ReplayIcon />} disabled={busy}
                  onClick={() => resume.mutate(id)}>
            Resume
          </Button>
        )}
        <Button size="small" variant="outlined" color="error"
                startIcon={<RestartAltIcon />} disabled={busy}
                onClick={() => setConfirmRestart(true)}>
          Restart
        </Button>
      </Stack>

      <Dialog open={confirmRestart} onClose={() => setConfirmRestart(false)}>
        <DialogTitle>Restart workflow #{id}?</DialogTitle>
        <DialogContent>
          <DialogContentText>
            This wipes every activity attempt for this workflow and runs it from
            scratch. The action cannot be undone.
          </DialogContentText>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setConfirmRestart(false)}>Cancel</Button>
          <Button color="error" variant="contained"
                  onClick={() => { restart.mutate(id); setConfirmRestart(false); }}>
            Restart
          </Button>
        </DialogActions>
      </Dialog>
    </>
  );
}
