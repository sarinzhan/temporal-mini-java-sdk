import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import {
  MutationCache,
  QueryCache,
  QueryClient,
  QueryClientProvider,
} from '@tanstack/react-query';
import { BrowserRouter } from 'react-router-dom';
import { CssBaseline, ThemeProvider } from '@mui/material';
import App from './App';
import { theme } from './theme';
import { AuthProvider } from './contexts/AuthContext';
import { RefreshIntervalProvider } from './contexts/RefreshIntervalContext';
import { BackendProvider } from './contexts/BackendContext';
import { ToastHost } from './components/Toast/ToastHost';
import { describeError, toastBus } from './utils/toastBus';
import { ApiError } from './api/client';

// Centralised error reporting — both for queries and mutations. 401s are part
// of the auth flow, not a "server unreachable" event, so we let the AuthContext
// handle them silently.
const queryClient = new QueryClient({
  queryCache: new QueryCache({
    onError: (error) => {
      if (error instanceof ApiError && error.status === 401) return;
      toastBus.push(describeError(error), 'error');
    },
  }),
  mutationCache: new MutationCache({
    onError: (error) => {
      if (error instanceof ApiError && error.status === 401) return;
      toastBus.push(describeError(error), 'error');
    },
  }),
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
          <BackendProvider>
            <AuthProvider>
              <RefreshIntervalProvider>
                <App />
                <ToastHost />
              </RefreshIntervalProvider>
            </AuthProvider>
          </BackendProvider>
        </BrowserRouter>
      </QueryClientProvider>
    </ThemeProvider>
  </StrictMode>,
);
