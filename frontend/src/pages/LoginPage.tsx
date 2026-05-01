import { useState, type FormEvent } from 'react';
import { Box, Button, Container, Paper, TextField, Typography, Alert } from '@mui/material';
import { useLocation, useNavigate } from 'react-router-dom';
import { useAuth } from '../contexts/AuthContext';
import { ApiError } from '../api/client';

interface LocationState { from?: { pathname?: string } }

export function LoginPage() {
  const { login } = useAuth();
  const navigate  = useNavigate();
  const location  = useLocation();

  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError]       = useState<string | null>(null);
  const [busy, setBusy]         = useState(false);

  async function onSubmit(e: FormEvent) {
    e.preventDefault();
    setError(null);
    setBusy(true);
    try {
      await login({ username, password });
      const dest = (location.state as LocationState | null)?.from?.pathname ?? '/workflows';
      navigate(dest, { replace: true });
    } catch (ex) {
      setError(ex instanceof ApiError ? ex.message : 'Login failed');
    } finally {
      setBusy(false);
    }
  }

  return (
    <Container maxWidth="xs" sx={{ mt: 10 }}>
      <Paper sx={{ p: 4 }}>
        <Typography variant="h6" sx={{ mb: 3, fontWeight: 700 }}>
          ⚡ temporal-mini
        </Typography>
        <Box component="form" onSubmit={onSubmit} sx={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
          <TextField
            label="Username"
            value={username}
            onChange={(e) => setUsername(e.target.value)}
            autoFocus
            required
            size="small"
          />
          <TextField
            label="Password"
            type="password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            required
            size="small"
          />
          {error && <Alert severity="error">{error}</Alert>}
          <Button type="submit" variant="contained" disabled={busy}>
            {busy ? 'Signing in…' : 'Sign in'}
          </Button>
        </Box>
      </Paper>
    </Container>
  );
}
