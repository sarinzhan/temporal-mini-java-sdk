import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { baseUrl } from '../utils/baseUrl';

const BACKENDS_KEY = 'temporal-mini.backends';
const SELECTED_KEY = 'temporal-mini.selectedBackend';

export interface Backend {
  id: string;
  name: string;
  /**
   * Either an absolute URL (e.g. {@code https://prod.example.com/temporal-mini/api})
   * or a same-origin path ({@code /temporal-mini/api}). Cross-origin URLs require
   * the target server to allow CORS from the SPA's host.
   */
  baseUrl: string;
}

export const DEFAULT_BACKEND: Backend = {
  id: 'local',
  name: 'Local',
  baseUrl: '/temporal-mini/api',
};

interface ContextValue {
  backends: Backend[];
  selected: Backend;
  select(id: string): void;
  add(b: Omit<Backend, 'id'>): void;
  remove(id: string): void;
}

const BackendContext = createContext<ContextValue | null>(null);

export function BackendProvider({ children }: { children: ReactNode }) {
  const [backends, setBackends] = useState<Backend[]>(() => readInitialBackends());
  const [selectedId, setSelectedId] = useState<string>(() => readInitialSelected(backends));
  const qc = useQueryClient();

  // Keep the module-level base URL in sync, persist, and clear cached server data
  // whenever the user switches backends so no stale rows leak across environments.
  const selected = backends.find((b) => b.id === selectedId) ?? backends[0] ?? DEFAULT_BACKEND;

  useEffect(() => {
    baseUrl.set(selected.baseUrl);
    qc.clear();
  }, [selected.baseUrl, qc]);

  useEffect(() => {
    localStorage.setItem(BACKENDS_KEY, JSON.stringify(backends));
  }, [backends]);
  useEffect(() => {
    localStorage.setItem(SELECTED_KEY, selectedId);
  }, [selectedId]);

  const select = useCallback((id: string) => setSelectedId(id), []);
  const add    = useCallback((b: Omit<Backend, 'id'>) => {
    const id = `b-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 5)}`;
    setBackends((prev) => [...prev, { id, ...b }]);
    setSelectedId(id);
  }, []);
  const remove = useCallback((id: string) => {
    setBackends((prev) => {
      const next = prev.filter((b) => b.id !== id);
      // If the removed one was selected, fall back to the first remaining (or default).
      if (id === selectedId) {
        const fallback = next[0]?.id ?? DEFAULT_BACKEND.id;
        setSelectedId(fallback);
      }
      return next.length === 0 ? [DEFAULT_BACKEND] : next;
    });
  }, [selectedId]);

  const value = useMemo(() => ({ backends, selected, select, add, remove }),
                        [backends, selected, select, add, remove]);

  return <BackendContext.Provider value={value}>{children}</BackendContext.Provider>;
}

export function useBackend() {
  const ctx = useContext(BackendContext);
  if (!ctx) throw new Error('useBackend outside BackendProvider');
  return ctx;
}

function readInitialBackends(): Backend[] {
  try {
    const raw = localStorage.getItem(BACKENDS_KEY);
    if (raw) {
      const parsed = JSON.parse(raw);
      if (Array.isArray(parsed) && parsed.length > 0) return parsed as Backend[];
    }
  } catch { /* ignore */ }
  return [DEFAULT_BACKEND];
}

function readInitialSelected(backends: Backend[]): string {
  const raw = localStorage.getItem(SELECTED_KEY);
  if (raw && backends.some((b) => b.id === raw)) return raw;
  return backends[0]?.id ?? DEFAULT_BACKEND.id;
}
