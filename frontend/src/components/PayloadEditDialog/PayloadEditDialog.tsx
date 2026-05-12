import { useEffect, useState } from 'react';
import {
  Alert,
  Button,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  FormControlLabel,
  Stack,
  Switch,
  TextField,
  Typography,
} from '@mui/material';

interface Props {
  open: boolean;
  title: string;
  initialValue: string | null | undefined;
  onClose: () => void;
  onSave: (payload: string) => void;
  saving?: boolean;
}

/**
 * JSON-aware payload editor. Operator can paste raw text or pretty-printed JSON;
 * the dialog validates as JSON when the toggle is on. Submitting saves whatever
 * is in the textarea verbatim — backend stores it as a string.
 */
export function PayloadEditDialog({
  open, title, initialValue, onClose, onSave, saving = false,
}: Props) {
  const [text, setText]   = useState('');
  const [jsonOnly, setJsonOnly] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!open) return;
    setText(prettyOrRaw(initialValue ?? ''));
    setError(null);
  }, [open, initialValue]);

  function tryParse(): boolean {
    if (!jsonOnly) { setError(null); return true; }
    try { JSON.parse(text); setError(null); return true; }
    catch (e) { setError(e instanceof Error ? e.message : 'Invalid JSON'); return false; }
  }

  function handleSave() {
    if (!tryParse()) return;
    onSave(text);
  }

  return (
    <Dialog open={open} onClose={onClose} maxWidth="md" fullWidth>
      <DialogTitle>{title}</DialogTitle>
      <DialogContent>
        <Stack spacing={1.5} sx={{ mt: 1 }}>
          <FormControlLabel
            control={<Switch checked={jsonOnly} onChange={(e) => setJsonOnly(e.target.checked)} />}
            label="Validate as JSON"
          />
          <TextField
            multiline
            minRows={10}
            maxRows={24}
            fullWidth
            value={text}
            onChange={(e) => setText(e.target.value)}
            error={!!error}
            sx={{
              '& textarea': {
                fontFamily: 'SFMono-Regular, Consolas, monospace',
                fontSize: 12,
                lineHeight: 1.5,
              },
            }}
          />
          {error
            ? <Alert severity="error" variant="outlined">{error}</Alert>
            : <Typography variant="caption" sx={{ color: 'text.disabled' }}>
                Stored verbatim as a string. Empty value clears the field.
              </Typography>}
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose} disabled={saving}>Cancel</Button>
        <Button variant="contained" onClick={handleSave} disabled={saving}>
          {saving ? 'Saving…' : 'Save'}
        </Button>
      </DialogActions>
    </Dialog>
  );
}

function prettyOrRaw(s: string): string {
  if (!s) return '';
  try { return JSON.stringify(JSON.parse(s), null, 2); }
  catch { return s; }
}
