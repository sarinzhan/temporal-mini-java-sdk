import { Button, Stack } from '@mui/material';
import PlayArrowIcon from '@mui/icons-material/PlayArrow';
import PauseIcon from '@mui/icons-material/Pause';
import ReplayIcon from '@mui/icons-material/Replay';
import { useWorkflowControls } from '../../hooks/useWorkflowControls';
import type { Workflow } from '../../types/workflow';

interface Props {
  workflow: Workflow;
}

/**
 * Run-now / Block / Unblock buttons. Visibility mirrors the engine's allowed
 * transitions ({@code WorkflowEngine.RUN_NOW_FORBIDDEN / BLOCKABLE / UNBLOCKABLE}).
 */
export function WorkflowControls({ workflow }: Props) {
  const { runNow, block, unblock } = useWorkflowControls();
  const id = workflow.id;
  const busy = runNow.isPending || block.isPending || unblock.isPending;

  return (
    <Stack direction="row" spacing={1}>
      {workflow.state !== 'FINISHED' && (
        <Button
          size="small"
          variant="contained"
          color="success"
          startIcon={<PlayArrowIcon />}
          onClick={() => runNow.mutate(id)}
          disabled={busy}
        >
          Run now
        </Button>
      )}
      {(workflow.state === 'NEW' || workflow.state === 'RUNNABLE') && (
        <Button
          size="small"
          variant="contained"
          color="warning"
          startIcon={<PauseIcon />}
          onClick={() => block.mutate(id)}
          disabled={busy}
        >
          Stop
        </Button>
      )}
      {workflow.state === 'BLOCKED' && (
        <Button
          size="small"
          variant="contained"
          color="secondary"
          startIcon={<ReplayIcon />}
          onClick={() => unblock.mutate(id)}
          disabled={busy}
        >
          Resume
        </Button>
      )}
    </Stack>
  );
}
