import { Box, Paper, Typography } from '@mui/material';
import type { MouseEvent } from 'react';
import { statusColors } from '../../theme';

interface Props {
  label: string;
  value: number | string;
  state: keyof typeof statusColors | 'ALL';
  active: boolean;
  onClick: (e: MouseEvent) => void;
}

export function StatCard({ label, value, state, active, onClick }: Props) {
  const color = state === 'ALL' ? '#6c8eff' : statusColors[state];
  return (
    <Paper
      onClick={onClick}
      sx={{
        p: 2,
        display: 'flex',
        alignItems: 'center',
        gap: 1.5,
        cursor: 'pointer',
        userSelect: 'none',
        border: 2,
        borderColor: active ? color : 'transparent',
        transition: 'border-color .15s, box-shadow .15s',
        '&:hover': { boxShadow: 3 },
      }}
    >
      {state !== 'ALL' && (
        <Box sx={{ width: 12, height: 12, borderRadius: '50%', bgcolor: color, flexShrink: 0 }} />
      )}
      <Box>
        <Typography variant="caption" sx={{ color: 'text.secondary', textTransform: 'uppercase', fontWeight: 600, letterSpacing: 0.5 }}>
          {label}
        </Typography>
        <Typography variant="h5" sx={{ fontWeight: 700, color, lineHeight: 1 }}>
          {value}
        </Typography>
      </Box>
    </Paper>
  );
}
