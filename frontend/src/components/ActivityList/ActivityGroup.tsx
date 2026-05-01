import { useState } from 'react';
import {
  Box,
  Chip,
  Collapse,
  IconButton,
  Stack,
  Typography,
} from '@mui/material';
import ExpandMoreIcon from '@mui/icons-material/ExpandMore';
import ChevronRightIcon from '@mui/icons-material/ChevronRight';
import type { Activity } from '../../types/activity';
import { fmtDate, fmtDuration } from '../../utils/format';
import { JsonViewer } from '../JsonViewer/JsonViewer';

interface Props {
  name: string;
  attempts: Activity[];
}

export function ActivityGroup({ name, attempts }: Props) {
  const [open, setOpen] = useState(false);
  const last = attempts[attempts.length - 1];
  const ok = attempts.some((a) => a.success);
  const failed = attempts.filter((a) => !a.success).length;

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
      </Stack>

      <Collapse in={open}>
        {attempts.map((a) => (
          <ActivityRow key={a.id} activity={a} groupName={name} />
        ))}
      </Collapse>
    </Box>
  );
}

function ActivityRow({ activity, groupName }: { activity: Activity; groupName: string }) {
  const [expanded, setExpanded] = useState(false);
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
          <Field label="Response">
            <JsonViewer raw={activity.outputPayload} title={`${groupName} #${activity.attempt} response`} />
          </Field>
          {activity.inputPayload && (
            <Field label="Input">
              <JsonViewer raw={activity.inputPayload} title={`${groupName} #${activity.attempt} input`} />
            </Field>
          )}
        </Box>
      </Collapse>
    </Box>
  );
}

function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <Stack direction="row" spacing={1.5} sx={{ mb: 1.25 }}>
      <Typography variant="caption" sx={{
        width: 110,
        color: 'text.disabled',
        fontWeight: 600,
        textTransform: 'uppercase',
        flexShrink: 0,
        pt: 0.25,
      }}>{label}</Typography>
      <Box sx={{ flex: 1, wordBreak: 'break-word' }}>{children}</Box>
    </Stack>
  );
}
