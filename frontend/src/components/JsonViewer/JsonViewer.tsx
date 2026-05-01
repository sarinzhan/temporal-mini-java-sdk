import { useState } from 'react';
import { Box, Dialog, DialogContent, DialogTitle, IconButton, Typography } from '@mui/material';
import CloseIcon from '@mui/icons-material/Close';

interface Props {
  raw: string | null | undefined;
  /** Title for the expanded JSON dialog. */
  title: string;
}

/**
 * Inline JSON preview that expands to a full dialog on click. Shows a placeholder
 * for empty/missing payloads. JSON parsing is best-effort — if the value isn't
 * JSON, we render it as-is.
 */
export function JsonViewer({ raw, title }: Props) {
  const [open, setOpen] = useState(false);
  if (raw == null || raw === '') {
    return <Typography variant="body2" sx={{ color: 'text.disabled', fontStyle: 'italic' }}>—</Typography>;
  }
  const pretty = tryPretty(raw) ?? raw;
  return (
    <>
      <Box
        onClick={() => setOpen(true)}
        sx={{
          bgcolor: '#1f2330',
          color: '#e5e7eb',
          p: 1.25,
          borderRadius: 1,
          fontFamily: 'SFMono-Regular, Consolas, monospace',
          fontSize: 11,
          maxHeight: 140,
          overflow: 'hidden',
          cursor: 'pointer',
          whiteSpace: 'pre',
          lineHeight: 1.45,
          '&:hover': { boxShadow: '0 0 0 2px #6c8eff' },
        }}
      >{pretty}</Box>
      <Dialog open={open} onClose={() => setOpen(false)} maxWidth="md" fullWidth>
        <DialogTitle sx={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
          {title}
          <IconButton onClick={() => setOpen(false)} size="small"><CloseIcon /></IconButton>
        </DialogTitle>
        <DialogContent>
          <Box sx={{
            bgcolor: '#1f2330',
            color: '#e5e7eb',
            p: 2,
            borderRadius: 1,
            fontFamily: 'SFMono-Regular, Consolas, monospace',
            fontSize: 12,
            whiteSpace: 'pre',
            overflow: 'auto',
            maxHeight: '70vh',
          }}>{pretty}</Box>
        </DialogContent>
      </Dialog>
    </>
  );
}

function tryPretty(s: string): string | null {
  try { return JSON.stringify(JSON.parse(s), null, 2); }
  catch { return null; }
}
