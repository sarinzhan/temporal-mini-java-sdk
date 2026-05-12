import { useEffect, useState } from 'react';
import { Alert, Snackbar } from '@mui/material';
import { toastBus, type ToastEvent } from '../../utils/toastBus';

/**
 * Singleton Snackbar that shows whatever {@code toastBus.push} emits — typically
 * QueryClient errors (network failure, 5xx). One instance, mounted near the
 * router root.
 */
export function ToastHost() {
  const [event, setEvent] = useState<ToastEvent | null>(null);

  useEffect(() => toastBus.subscribe(setEvent), []);

  return (
    <Snackbar
      open={!!event}
      autoHideDuration={5000}
      anchorOrigin={{ vertical: 'bottom', horizontal: 'right' }}
      onClose={() => setEvent(null)}
    >
      {event ? (
        <Alert severity={event.severity} variant="filled" onClose={() => setEvent(null)}>
          {event.message}
        </Alert>
      ) : undefined}
    </Snackbar>
  );
}
