import { useEffect, useRef, useState } from 'react';

export interface Toast {
  id: number;
  message: string;
}

type Listener = (toast: Toast) => void;
const listeners = new Set<Listener>();
let counter = 0;

export function toast(message: string) {
  const t: Toast = { id: ++counter, message };
  listeners.forEach((l) => l(t));
}

export function Toaster() {
  const [toasts, setToasts] = useState<Toast[]>([]);
  const timers = useRef<Map<number, ReturnType<typeof setTimeout>>>(new Map());

  useEffect(() => {
    const listener: Listener = (t) => {
      setToasts((prev) => [...prev, t]);
      const timer = setTimeout(() => {
        setToasts((prev) => prev.filter((x) => x.id !== t.id));
        timers.current.delete(t.id);
      }, 5000);
      timers.current.set(t.id, timer);
    };
    listeners.add(listener);
    return () => { listeners.delete(listener); };
  }, []);

  const dismiss = (id: number) => {
    clearTimeout(timers.current.get(id));
    timers.current.delete(id);
    setToasts((prev) => prev.filter((x) => x.id !== id));
  };

  if (!toasts.length) return null;

  return (
    <div className="fixed bottom-4 right-4 z-50 flex flex-col gap-2">
      {toasts.map((t) => (
        <div
          key={t.id}
          className="flex items-start gap-3 rounded-lg border border-danger/30 bg-bg-surface px-4 py-3 shadow-lg text-sm text-danger max-w-sm"
        >
          <span className="flex-1">{t.message}</span>
          <button
            onClick={() => dismiss(t.id)}
            className="mt-0.5 shrink-0 text-fg-muted hover:text-fg"
          >
            ✕
          </button>
        </div>
      ))}
    </div>
  );
}
