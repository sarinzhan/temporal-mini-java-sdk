import { ToggleButton, ToggleButtonGroup } from '@mui/material';
import { WINDOWS } from './chartUtils';
import type { WindowKey } from '../../types/metric';

interface Props {
  value: WindowKey;
  onChange: (next: WindowKey) => void;
}

export function WindowPicker({ value, onChange }: Props) {
  return (
    <ToggleButtonGroup
      value={value}
      exclusive
      size="small"
      onChange={(_, v: WindowKey | null) => { if (v) onChange(v); }}
    >
      {WINDOWS.map((w) => (
        <ToggleButton key={w.key} value={w.key}>{w.label}</ToggleButton>
      ))}
    </ToggleButtonGroup>
  );
}
