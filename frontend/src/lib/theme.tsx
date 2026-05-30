import { createContext, useCallback, useContext, useEffect, useState, type ReactNode } from 'react';

type Theme = 'system' | 'light' | 'dark';

interface ThemeCtx {
  theme: Theme;
  resolved: 'light' | 'dark';
  setTheme: (t: Theme) => void;
}

const Ctx = createContext<ThemeCtx | null>(null);

function resolve(t: Theme): 'light' | 'dark' {
  if (t === 'system') {
    return window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
  }
  return t;
}

export function ThemeProvider({ children }: { children: ReactNode }) {
  const [theme, setThemeState] = useState<Theme>(() => (localStorage.getItem('wf-theme') as Theme) ?? 'system');
  const [resolved, setResolved] = useState<'light' | 'dark'>(() => resolve(theme));

  const setTheme = useCallback((t: Theme) => {
    localStorage.setItem('wf-theme', t);
    setThemeState(t);
  }, []);

  useEffect(() => {
    const r = resolve(theme);
    setResolved(r);
    document.documentElement.setAttribute('data-theme', r);
  }, [theme]);

  useEffect(() => {
    if (theme !== 'system') return;
    const mq = window.matchMedia('(prefers-color-scheme: dark)');
    const handler = () => {
      const r = resolve('system');
      setResolved(r);
      document.documentElement.setAttribute('data-theme', r);
    };
    mq.addEventListener('change', handler);
    return () => mq.removeEventListener('change', handler);
  }, [theme]);

  return <Ctx.Provider value={{ theme, resolved, setTheme }}>{children}</Ctx.Provider>;
}

export function useTheme() {
  const v = useContext(Ctx);
  if (!v) throw new Error('useTheme must be used within ThemeProvider');
  return v;
}
