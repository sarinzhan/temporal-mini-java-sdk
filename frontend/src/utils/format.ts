/**
 * Date helpers shared by the UI. Backend serialises LocalDateTime either as ISO
 * string or as `[y, m, d, h, m, s, ns]` depending on Jackson config — handle both.
 */
export type DateLike = string | number[] | null | undefined;

export function toDate(dt: DateLike): Date | null {
  if (dt == null) return null;
  if (Array.isArray(dt)) {
    const [y, mo, d, h = 0, mi = 0, s = 0] = dt;
    return new Date(y, mo - 1, d, h, mi, s);
  }
  const parsed = new Date(dt);
  return Number.isNaN(parsed.getTime()) ? null : parsed;
}

export function fmtDate(dt: DateLike): string {
  const d = toDate(dt);
  if (!d) return '—';
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ` +
         `${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`;
}

export function fmtElapsed(ms: number): string {
  const s = Math.max(0, Math.round(ms / 1000));
  if (s < 60) return `${s}s`;
  if (s < 600) {
    const m = Math.floor(s / 60), r = s % 60;
    return `${m}:${String(r).padStart(2, '0')} min`;
  }
  if (s < 3600) return `${Math.floor(s / 60)} min`;
  if (s < 86400) {
    const h = Math.floor(s / 3600), m = Math.floor((s % 3600) / 60);
    return `${h}h ${m}m`;
  }
  const dd = Math.floor(s / 86400), h = Math.floor((s % 86400) / 3600);
  return `${dd}d ${h}h`;
}

export function fmtDuration(start: DateLike, end: DateLike): string {
  const a = toDate(start);
  const b = toDate(end);
  if (!a || !b) return '—';
  const ms = b.getTime() - a.getTime();
  if (ms < 0) return '—';
  if (ms < 1000) return `${ms}ms`;
  return fmtElapsed(ms);
}
