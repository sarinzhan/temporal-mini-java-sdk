import { Chip } from '@mui/material';
import { statusColors } from '../../theme';
import type { VisualWorkflowState } from '../../types/workflow';

interface Props {
  state: VisualWorkflowState;
}

export function StatusBadge({ state }: Props) {
  const color = statusColors[state] ?? '#6b7280';
  return (
    <Chip
      label={state}
      size="small"
      sx={{
        bgcolor: `${color}22`,
        color,
        fontWeight: 700,
        letterSpacing: 0.3,
        fontSize: 11,
        height: 22,
      }}
    />
  );
}
