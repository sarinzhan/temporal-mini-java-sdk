import { useState } from 'react';
import {
  Box,
  Button,
  Chip,
  Collapse,
  Dialog,
  DialogActions,
  DialogContent,
  DialogContentText,
  DialogTitle,
  IconButton,
  Stack,
  Typography,
} from '@mui/material';
import ExpandMoreIcon from '@mui/icons-material/ExpandMore';
import ChevronRightIcon from '@mui/icons-material/ChevronRight';
import RestartAltIcon from '@mui/icons-material/RestartAlt';
import EditIcon from '@mui/icons-material/Edit';
import type { Activity } from '../../types/activity';
import { fmtDate, fmtDuration } from '../../utils/format';
import { JsonViewer } from '../JsonViewer/JsonViewer';
import { PayloadEditDialog } from '../PayloadEditDialog/PayloadEditDialog';
import { useWorkflowControls } from '../../hooks/useWorkflowControls';
import { useEditPayload } from '../../hooks/useEditPayload';

interface Props {
  workflowId: number;
  name: string;
  attempts: Activity[];
}

export function ActivityGroup({ workflowId, name, attempts }: Props) {
  const { restartFromActivity } = useWorkflowControls();
  const [confirm, setConfirm] = useState(false);
  const earliest = attempts[0];
  const [open, setOpen] = useState(false);
  const last = attempts[attempts.length - 1];
  const ok = attempts.some((a) => a.success);
  const failed = attempts.filter((a) => !a.success).length;
  void earliest; // referenced below in the dialog handler

  const status = ok
    ? <Chip size="small" label="OK"      color="success" sx={{ fontWeight: 700 }} />
    : failed > 0
    ? <Chip size="small" label="FAILING" color="error"   sx={{ fontWeight: 700 }} />
    : <Chip size="small" label="—" />;

  return (
    <Box sx={{ border: 1, borderColor: 'divider', borderRadius: 2, mb: 1.25, overflow: 'hidden' }}>
      <Stack
        direction="row"
        alignItems="center"
        spacing={1.25}
        sx={{ px: 1.5, py: 1, bgcolor: 'action.hover', cursor: 'pointer' }}
        onClick={() => setOpen((v) => !v)}
      >
        <IconButton size="small">
          {open ? <ExpandMoreIcon fontSize="small" /> : <ChevronRightIcon fontSize="small" />}
        </IconButton>
        <Typography sx={{ fontFamily: 'SFMono-Regular, Consolas, monospace', fontWeight: 700 }}>
          {name}
        </Typography>
        {status}
        <Chip size="small" label={`${attempts.length} attempt${attempts.length === 1 ? '' : 's'}`} variant="outlined" />
        <Box sx={{ flex: 1 }} />
        <Typography variant="caption" sx={{ color: 'text.disabled' }}>
          last {fmtDate(last?.finishedAt ?? last?.startedAt)}
        </Typography>
        <Button
          size="small"
          variant="outlined"
          color="error"
          startIcon={<RestartAltIcon />}
          onClick={(e) => { e.stopPropagation(); setConfirm(true); }}
          disabled={restartFromActivity.isPending}
        >
          Restart from here
        </Button>
      </Stack>

      <Collapse in={open}>
        {attempts.map((a) => (
          <ActivityRow key={a.id} workflowId={workflowId} activity={a} groupName={name} />
        ))}
      </Collapse>

      <Dialog open={confirm} onClose={() => setConfirm(false)}>
        <DialogTitle>Restart from "{name}"?</DialogTitle>
        <DialogContent>
          <DialogContentText>
            Every activity attempt at or after this one (including {' '}
            <strong>{name}</strong> itself, all {attempts.length} attempt(s))
            will be deleted. The workflow rewinds to this point and resumes from
            here on the next scheduler tick. Earlier successful activities
            remain cached and will not re-run.
          </DialogContentText>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setConfirm(false)}>Cancel</Button>
          <Button
            color="error"
            variant="contained"
            onClick={() => {
              restartFromActivity.mutate({ id: workflowId, activityId: earliest.id });
              setConfirm(false);
            }}
          >
            Restart
          </Button>
        </DialogActions>
      </Dialog>
    </Box>
  );
}

function ActivityRow({ workflowId, activity, groupName }:
                     { workflowId: number; activity: Activity; groupName: string }) {
  const [expanded, setExpanded] = useState(false);
  const [editing, setEditing]   = useState<null | 'input' | 'output'>(null);
  const { setActivityPayload } = useEditPayload(workflowId);

  return (
    <Box sx={{ borderTop: 1, borderColor: 'divider' }}>
      <Stack
        direction="row"
        spacing={1.5}
        alignItems="center"
        sx={{ px: 2, py: 1, fontSize: 12, cursor: 'pointer', '&:hover': { bgcolor: 'action.hover' } }}
        onClick={() => setExpanded((v) => !v)}
      >
        <Box sx={{ width: 60, fontWeight: 600 }}>#{activity.attempt}</Box>
        <Box sx={{ width: 90 }}>
          {activity.success
            ? <Typography variant="body2" sx={{ color: 'success.main', fontWeight: 700 }}>✓ OK</Typography>
            : <Typography variant="body2" sx={{ color: 'error.main',   fontWeight: 700 }}>✗ FAIL</Typography>}
        </Box>
        <Box sx={{ width: 150, color: 'text.secondary', fontFamily: 'SFMono-Regular, Consolas, monospace' }}>
          {fmtDate(activity.startedAt)}
        </Box>
        <Box sx={{ width: 150, color: 'text.secondary', fontFamily: 'SFMono-Regular, Consolas, monospace' }}>
          {fmtDate(activity.finishedAt)}
        </Box>
        <Box sx={{ width: 90, fontWeight: 600, fontFamily: 'SFMono-Regular, Consolas, monospace' }}>
          {fmtDuration(activity.startedAt, activity.finishedAt)}
        </Box>
        <Box sx={{
          flex: 1,
          color: 'error.main',
          overflow: 'hidden',
          textOverflow: 'ellipsis',
          whiteSpace: 'nowrap',
        }} title={activity.errorMessage ?? ''}>
          {activity.errorMessage ?? (activity.success ? '' : '')}
        </Box>
      </Stack>

      <Collapse in={expanded}>
        <Box sx={{ px: 3, py: 2, bgcolor: 'action.hover' }}>
          {activity.errorMessage && (
            <Field label="Error">
              <Typography variant="body2" sx={{ color: 'error.main' }}>{activity.errorMessage}</Typography>
            </Field>
          )}
          <Field label="Response" trailing={
            <Button size="small" startIcon={<EditIcon fontSize="small" />} onClick={() => setEditing('output')} sx={{ minWidth: 0 }}>
              Edit
            </Button>
          }>
            <JsonViewer raw={activity.outputPayload} title={`${groupName} #${activity.attempt} response`} />
          </Field>
          <Field label="Input" trailing={
            <Button size="small" startIcon={<EditIcon fontSize="small" />} onClick={() => setEditing('input')} sx={{ minWidth: 0 }}>
              Edit
            </Button>
          }>
            <JsonViewer raw={activity.inputPayload} title={`${groupName} #${activity.attempt} input`} />
          </Field>
        </Box>
      </Collapse>

      <PayloadEditDialog
        open={editing !== null}
        title={editing === 'output'
          ? `Edit response — ${groupName} #${activity.attempt}`
          : `Edit input — ${groupName} #${activity.attempt}`}
        initialValue={editing === 'output' ? activity.outputPayload : activity.inputPayload}
        saving={setActivityPayload.isPending}
        onClose={() => setEditing(null)}
        onSave={(payload) => {
          if (editing == null) return;
          setActivityPayload.mutate(
            { activityId: activity.id, field: editing, payload },
            { onSuccess: () => setEditing(null) },
          );
        }}
      />
    </Box>
  );
}

function Field({ label, children, trailing }: {
  label: string;
  children: React.ReactNode;
  trailing?: React.ReactNode;
}) {
  return (
    <Stack direction="row" spacing={1.5} sx={{ mb: 1.25 }} onClick={(e) => e.stopPropagation()}>
      <Stack direction="row" alignItems="center" spacing={0.5} sx={{ width: 110, flexShrink: 0, pt: 0.25 }}>
        <Typography variant="caption" sx={{
          color: 'text.disabled',
          fontWeight: 600,
          textTransform: 'uppercase',
        }}>{label}</Typography>
        {trailing}
      </Stack>
      <Box sx={{ flex: 1, wordBreak: 'break-word' }}>{children}</Box>
    </Stack>
  );
}
