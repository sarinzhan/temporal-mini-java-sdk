import { useState } from 'react';
import {
  Alert,
  Button,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  IconButton,
  List,
  ListItem,
  ListItemText,
  Stack,
  TextField,
  Typography,
} from '@mui/material';
import DeleteIcon from '@mui/icons-material/Delete';
import { useBackend } from '../../contexts/BackendContext';

interface Props {
  open: boolean;
  onClose: () => void;
}

/**
 * Add/remove backend environments. Operator types a name and a base URL; the
 * SPA stores the list in localStorage and picks the new entry automatically.
 *
 * <p>Cross-origin URLs need the target server to send appropriate CORS headers
 * (and {@code Access-Control-Allow-Credentials: true} for cookie-based auth).
 */
export function BackendsManageDialog({ open, onClose }: Props) {
  const { backends, add, remove } = useBackend();
  const [name, setName] = useState('');
  const [url, setUrl]   = useState('');
  const [error, setError] = useState<string | null>(null);

  function handleAdd() {
    const trimmedName = name.trim();
    const trimmedUrl  = url.trim().replace(/\/$/, '');
    if (!trimmedName || !trimmedUrl) {
      setError('Name and base URL are both required.');
      return;
    }
    if (!/^(https?:\/\/|\/)/.test(trimmedUrl)) {
      setError('Base URL must start with http(s):// or /');
      return;
    }
    add({ name: trimmedName, baseUrl: trimmedUrl });
    setName('');
    setUrl('');
    setError(null);
  }

  return (
    <Dialog open={open} onClose={onClose} maxWidth="sm" fullWidth>
      <DialogTitle>Manage backends</DialogTitle>
      <DialogContent>
        <Stack spacing={2} sx={{ mt: 1 }}>
          <List dense disablePadding>
            {backends.map((b) => (
              <ListItem
                key={b.id}
                disableGutters
                secondaryAction={
                  backends.length > 1 && (
                    <IconButton edge="end" onClick={() => remove(b.id)}>
                      <DeleteIcon />
                    </IconButton>
                  )
                }
              >
                <ListItemText
                  primary={b.name}
                  secondary={b.baseUrl}
                  primaryTypographyProps={{ fontWeight: 600 }}
                  secondaryTypographyProps={{
                    sx: { fontFamily: 'SFMono-Regular, Consolas, monospace', fontSize: 12 },
                  }}
                />
              </ListItem>
            ))}
          </List>

          <Typography variant="subtitle2" sx={{ mt: 1 }}>Add a backend</Typography>
          <Stack direction="row" spacing={1}>
            <TextField label="Name" size="small"
                       value={name} onChange={(e) => setName(e.target.value)}
                       sx={{ flex: 1 }} />
            <TextField label="Base URL" size="small" placeholder="https://host/temporal-mini/api"
                       value={url} onChange={(e) => setUrl(e.target.value)}
                       sx={{ flex: 2 }} />
          </Stack>
          {error && <Alert severity="error" variant="outlined">{error}</Alert>}
          <Typography variant="caption" sx={{ color: 'text.disabled' }}>
            Cross-origin URLs require the backend to send CORS headers (with
            {' '}<code>Access-Control-Allow-Credentials: true</code> for the
            session cookie). Same-origin paths like
            {' '}<code>/temporal-mini/api</code> always work.
          </Typography>
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Close</Button>
        <Button variant="contained" onClick={handleAdd}>Add</Button>
      </DialogActions>
    </Dialog>
  );
}
