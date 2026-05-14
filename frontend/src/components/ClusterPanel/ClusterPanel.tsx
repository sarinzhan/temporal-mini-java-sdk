import { useState } from 'react';
import {
  Box, Chip, Collapse, IconButton, LinearProgress,
  Paper, Skeleton, Stack, Tooltip, Typography,
} from '@mui/material';
import ExpandMoreIcon from '@mui/icons-material/ExpandMore';
import ExpandLessIcon from '@mui/icons-material/ExpandLess';
import CircleIcon from '@mui/icons-material/Circle';
import { useCluster } from '../../hooks/useCluster';
import type { NodeState } from '../../types/cluster';

export function ClusterPanel() {
  const { data, isLoading } = useCluster();
  const [expanded, setExpanded] = useState<string | null>(null);

  if (isLoading) {
    return (
      <Paper sx={{ p: 2 }}>
        <Skeleton variant="text" width="30%" />
        <Skeleton variant="rectangular" height={40} sx={{ mt: 1 }} />
      </Paper>
    );
  }

  if (!data || data.nodes.length === 0) return null;

  const totalActive = data.nodes.reduce((s, n) => s + n.activeCount, 0);
  const totalQueue  = data.nodes.reduce((s, n) => s + n.queueSize, 0);
  const totalTasks  = data.nodes.reduce((s, n) => s + n.runningTasks.length, 0);

  return (
    <Paper sx={{ p: 2 }}>
      {/* Header row */}
      <Stack direction="row" alignItems="center" justifyContent="space-between" flexWrap="wrap" gap={1}>
        <Stack direction="row" alignItems="center" gap={1}>
          <Typography variant="caption" sx={{ textTransform: 'uppercase', fontWeight: 700, color: 'text.disabled', letterSpacing: 0.8 }}>
            Cluster
          </Typography>
          <Chip
            size="small"
            label={`${data.nodes.length} instance${data.nodes.length !== 1 ? 's' : ''}`}
            color="success"
            sx={{ height: 20, fontSize: 11 }}
          />
        </Stack>
        <Stack direction="row" gap={3}>
          <Summary label="Active workers" value={totalActive} />
          <Summary label="Queued tasks"   value={totalQueue} />
          <Summary label="Running"        value={totalTasks} />
        </Stack>
      </Stack>

      {/* Per-instance rows */}
      <Stack spacing={1} sx={{ mt: 1.5 }}>
        {data.nodes.map((node) => (
          <NodeRow
            key={node.nodeId}
            node={node}
            open={expanded === node.nodeId}
            onToggle={() => setExpanded(expanded === node.nodeId ? null : node.nodeId)}
          />
        ))}
      </Stack>
    </Paper>
  );
}

function Summary({ label, value }: { label: string; value: number }) {
  return (
    <Stack alignItems="flex-end">
      <Typography variant="caption" sx={{ color: 'text.disabled', textTransform: 'uppercase', fontSize: 10, letterSpacing: 0.6 }}>
        {label}
      </Typography>
      <Typography variant="body2" sx={{ fontWeight: 700, lineHeight: 1 }}>
        {value}
      </Typography>
    </Stack>
  );
}

function NodeRow({ node, open, onToggle }: { node: NodeState; open: boolean; onToggle: () => void }) {
  const hasRunning = node.runningTasks.length > 0;

  return (
    <Box sx={{ border: 1, borderColor: 'divider', borderRadius: 1, overflow: 'hidden' }}>
      {/* Collapsed row */}
      <Stack
        direction="row"
        alignItems="center"
        gap={1.5}
        sx={{ px: 1.5, py: 1, cursor: hasRunning ? 'pointer' : 'default', '&:hover': hasRunning ? { bgcolor: 'action.hover' } : {} }}
        onClick={hasRunning ? onToggle : undefined}
      >
        <Tooltip title="Instance online">
          <CircleIcon sx={{ fontSize: 10, color: 'success.main', flexShrink: 0 }} />
        </Tooltip>

        <Typography variant="body2" sx={{ fontWeight: 600, flex: 1, fontFamily: 'monospace', fontSize: 12 }}>
          {node.nodeUrl}
        </Typography>

        <NodeGauge label="Workers" value={node.activeCount} hint={`queue: ${node.queueSize}`} />

        <Chip
          size="small"
          label={`${node.runningTasks.length} running`}
          variant="outlined"
          color={node.runningTasks.length > 0 ? 'success' : 'default'}
          sx={{ height: 20, fontSize: 11, minWidth: 80 }}
        />

        {hasRunning && (
          <IconButton size="small" sx={{ p: 0 }}>
            {open ? <ExpandLessIcon fontSize="small" /> : <ExpandMoreIcon fontSize="small" />}
          </IconButton>
        )}
      </Stack>

      {/* Expanded: running tasks */}
      {hasRunning && (
        <Collapse in={open}>
          <Box sx={{ px: 2, pb: 1.5, bgcolor: 'action.hover' }}>
            <Typography variant="caption" sx={{ color: 'text.disabled', textTransform: 'uppercase', letterSpacing: 0.6, fontSize: 10 }}>
              Running workflows
            </Typography>
            <Stack spacing={0.5} sx={{ mt: 0.5 }}>
              {node.runningTasks.map((t) => (
                <Stack key={t.workflowId} direction="row" gap={2} alignItems="center">
                  <Typography variant="body2" sx={{ fontFamily: 'monospace', fontWeight: 600 }}>
                    #{t.workflowId}
                  </Typography>
                  <Typography variant="caption" sx={{ color: 'text.secondary' }}>
                    running for {formatElapsed(t.startedAtEpochMs)}
                  </Typography>
                </Stack>
              ))}
            </Stack>
          </Box>
        </Collapse>
      )}
    </Box>
  );
}

function NodeGauge({ label, value, hint }: { label: string; value: number; hint: string }) {
  return (
    <Stack sx={{ minWidth: 120 }}>
      <Stack direction="row" justifyContent="space-between">
        <Typography variant="caption" sx={{ color: 'text.secondary', fontSize: 10, textTransform: 'uppercase' }}>
          {label}
        </Typography>
        <Typography variant="caption" sx={{ color: 'text.disabled', fontSize: 10 }}>
          {hint}
        </Typography>
      </Stack>
      <Typography variant="caption" sx={{ fontWeight: 600 }}>{value} active</Typography>
      <LinearProgress
        variant="determinate"
        value={value > 0 ? Math.min(100, value * 10) : 0}
        color="warning"
        sx={{ height: 4, borderRadius: 1, mt: 0.25 }}
      />
    </Stack>
  );
}

function formatElapsed(startMs: number): string {
  const sec = Math.floor((Date.now() - startMs) / 1000);
  if (sec < 60) return `${sec}s`;
  const min = Math.floor(sec / 60);
  if (min < 60) return `${min}m ${sec % 60}s`;
  return `${Math.floor(min / 60)}h ${min % 60}m`;
}
