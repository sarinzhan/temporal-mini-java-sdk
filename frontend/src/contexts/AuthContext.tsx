import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
import { authApi } from '../api/auth';
import { ApiError } from '../api/client';
import { baseUrl } from '../utils/baseUrl';
import type { AuthUser, LoginRequest } from '../types/auth';

interface ContextValue {
  user: AuthUser | null;
  isLoading: boolean;
  login: (req: LoginRequest) => Promise<void>;
  logout: () => Promise<void>;
}

const AuthContext = createContext<ContextValue | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<AuthUser | null>(null);
  const [isLoading, setLoading] = useState(true);

  // Re-check whoami whenever the active backend changes (initial mount + subsequent
  // switches). Three outcomes:
  //  - 200 → logged in, store the user.
  //  - 401 → not logged in, redirect to /login (handled by ProtectedRoute).
  //  - 404 → auth is not enabled on the backend (workflow.ui.security.enabled=false);
  //          treat as an anonymous "always authenticated" session so the UI works.
  const recheck = useCallback(() => {
    let cancelled = false;
    setLoading(true);
    authApi.me()
      .then((u) => { if (!cancelled) setUser(u); })
      .catch((e) => {
        if (cancelled) return;
        if (e instanceof ApiError && e.status === 404) {
          setUser({ username: 'anonymous' });
        } else {
          if (!(e instanceof ApiError) || e.status !== 401) console.error(e);
          setUser(null);
        }
      })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, []);

  useEffect(() => {
    const cleanup = recheck();
    const unsub = baseUrl.subscribe(() => recheck());
    return () => { cleanup(); unsub(); };
  }, [recheck]);

  const login = useCallback(async (req: LoginRequest) => {
    const u = await authApi.login(req);
    setUser(u);
  }, []);

  const logout = useCallback(async () => {
    await authApi.logout();
    setUser(null);
  }, []);

  const value = useMemo(() => ({ user, isLoading, login, logout }), [user, isLoading, login, logout]);
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth() {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth outside AuthProvider');
  return ctx;
}
