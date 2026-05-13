import { createTheme } from '@mui/material/styles';

/**
 * Status palette — the same hues used in the legacy index.html, lifted to MUI tokens
 * so badges, status dots, and progress bars share a single source of truth.
 */
export const statusColors: Record<string, string> = {
  NEW:      '#6c8eff',
  IN_QUEUE: '#0ea5e9',
  WAITING:  '#94a3b8',
  RUNNING:  '#22c55e',
  RETRY:    '#f59e0b',
  STOPPED:  '#8b5cf6',
  FINISHED: '#3ecf8e',
  FAILED:   '#f87171',
};

export const theme = createTheme({
  palette: {
    mode: 'light',
    primary:   { main: '#6c8eff' },
    secondary: { main: '#1a1d23' },
    success:   { main: '#3ecf8e' },
    warning:   { main: '#f59e0b' },
    error:     { main: '#f87171' },
    background: { default: '#f5f6fa' },
  },
  shape: { borderRadius: 10 },
  typography: {
    fontFamily: '-apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif',
    fontSize: 13,
  },
});
