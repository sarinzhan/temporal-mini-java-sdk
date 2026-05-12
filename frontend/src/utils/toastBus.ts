/**
 * Tiny pub/sub used by the React Query cache to surface fetch errors to the
 * UI without coupling QueryClient creation to React state. The Snackbar host
 * subscribes once on mount and listens for emits from anywhere.
 */
type Severity = 'error' | 'success' | 'info';

export interface ToastEvent {
  message: string;
  severity: Severity;
}

type Listener = (e: ToastEvent) => void;

const listeners = new Set<Listener>();

export const toastBus = {
  push(message: string, severity: Severity = 'error') {
    listeners.forEach((l) => l({ message, severity }));
  },
  subscribe(l: Listener) {
    listeners.add(l);
    return () => { listeners.delete(l); };
  },
};

export function describeError(error: unknown): string {
  if (error instanceof Error) return error.message;
  if (typeof error === 'string') return error;
  return 'Server unreachable';
}
