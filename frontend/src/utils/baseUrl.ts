/**
 * Module-level mutable base URL for the API. Lives outside React so the {@code request}
 * helper in {@code api/client.ts} can read it on every call without prop-drilling.
 * The {@code BackendContext} provider keeps this in sync with the user's selection.
 */
const DEFAULT = '/temporal-mini/api';

let current = DEFAULT;
const listeners = new Set<(s: string) => void>();

export const baseUrl = {
  get: () => current,
  set: (s: string) => {
    if (s === current) return;
    current = s;
    listeners.forEach((l) => l(s));
  },
  subscribe(l: (s: string) => void) {
    listeners.add(l);
    return () => { listeners.delete(l); };
  },
};
