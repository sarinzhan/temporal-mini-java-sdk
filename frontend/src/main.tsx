import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { BrowserRouter } from 'react-router-dom';
import { CssBaseline, ThemeProvider } from '@mui/material';
import App from './App';
import { theme } from './theme';
import { AuthProvider } from './contexts/AuthContext';
import { RefreshIntervalProvider } from './contexts/RefreshIntervalContext';

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      retry: 1,
      refetchOnWindowFocus: false,
      staleTime: 1_000,
    },
  },
});

const rootEl = document.getElementById('root');
if (!rootEl) throw new Error('Missing #root');

createRoot(rootEl).render(
  <StrictMode>
    <ThemeProvider theme={theme}>
      <CssBaseline />
      <QueryClientProvider client={queryClient}>
        <BrowserRouter basename={import.meta.env.BASE_URL}>
          <AuthProvider>
            <RefreshIntervalProvider>
              <App />
            </RefreshIntervalProvider>
          </AuthProvider>
        </BrowserRouter>
      </QueryClientProvider>
    </ThemeProvider>
  </StrictMode>,
);
