import { createContext, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';

const STORAGE_KEY = 'temporal-mini.refreshIntervalMs';

/** 0 means polling is off (manual refresh only). */
export type RefreshIntervalMs = 0 | 2000 | 5000 | 10000 | 30000;
export const REFRESH_OPTIONS: RefreshIntervalMs[] = [0, 2000, 5000, 10000, 30000];

interface ContextValue {
  intervalMs: RefreshIntervalMs;
  setIntervalMs: (v: RefreshIntervalMs) => void;
}

const RefreshIntervalContext = createContext<ContextValue | null>(null);

export function RefreshIntervalProvider({ children }: { children: ReactNode }) {
  const [intervalMs, setIntervalMs] = useState<RefreshIntervalMs>(() => readInitial());

  useEffect(() => {
    localStorage.setItem(STORAGE_KEY, String(intervalMs));
  }, [intervalMs]);

  const value = useMemo(() => ({ intervalMs, setIntervalMs }), [intervalMs]);
  return (
    <RefreshIntervalContext.Provider value={value}>
      {children}
    </RefreshIntervalContext.Provider>
  );
}

export function useRefreshIntervalContext() {
  const ctx = useContext(RefreshIntervalContext);
  if (!ctx) throw new Error('useRefreshIntervalContext outside RefreshIntervalProvider');
  return ctx;
}

function readInitial(): RefreshIntervalMs {
  const raw = localStorage.getItem(STORAGE_KEY);
  const n = raw == null ? NaN : Number(raw);
  return (REFRESH_OPTIONS as number[]).includes(n) ? (n as RefreshIntervalMs) : 5000;
}
