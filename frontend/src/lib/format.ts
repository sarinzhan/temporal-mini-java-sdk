/** "15 May 2026 at 15:53:58 GMT+6" */
export function formatTimestamp(value: string | Date | null | undefined): string {
  if (!value) return '—';
  const d = typeof value === 'string' ? new Date(value) : value;
  if (Number.isNaN(d.getTime())) return '—';

  const day = d.getDate();
  const month = d.toLocaleString('en-US', { month: 'long' });
  const year = d.getFullYear();
  const hh = String(d.getHours()).padStart(2, '0');
  const mm = String(d.getMinutes()).padStart(2, '0');
  const ss = String(d.getSeconds()).padStart(2, '0');

  const offsetMin = -d.getTimezoneOffset();
  const sign = offsetMin >= 0 ? '+' : '-';
  const offsetH = Math.floor(Math.abs(offsetMin) / 60);
  const offsetM = Math.abs(offsetMin) % 60;
  const tz = offsetM === 0 ? `GMT${sign}${offsetH}` : `GMT${sign}${offsetH}:${String(offsetM).padStart(2, '0')}`;

  return `${day} ${month} ${year} at ${hh}:${mm}:${ss} ${tz}`;
}

/** "2d 4h 13m 42s 41ms" */
export function formatDuration(ms: number | null | undefined): string {
  if (ms == null || Number.isNaN(ms)) return '—';
  if (ms < 0) return '—';
  if (ms === 0) return '0ms';

  const days = Math.floor(ms / 86_400_000);
  ms -= days * 86_400_000;
  const hours = Math.floor(ms / 3_600_000);
  ms -= hours * 3_600_000;
  const minutes = Math.floor(ms / 60_000);
  ms -= minutes * 60_000;
  const seconds = Math.floor(ms / 1_000);
  const millis = ms - seconds * 1_000;

  const parts: string[] = [];
  if (days) parts.push(`${days}d`);
  if (hours) parts.push(`${hours}h`);
  if (minutes) parts.push(`${minutes}m`);
  if (seconds) parts.push(`${seconds}s`);
  if (millis || parts.length === 0) parts.push(`${millis}ms`);
  return parts.join(' ');
}

export function durationBetween(start: string | null | undefined, end: string | null | undefined): number | null {
  if (!start) return null;
  const s = new Date(start).getTime();
  const e = end ? new Date(end).getTime() : Date.now();
  if (Number.isNaN(s) || Number.isNaN(e)) return null;
  return e - s;
}

export function shortId(id: string, length = 8): string {
  if (!id) return '';
  return id.length > length ? `${id.slice(0, length)}…` : id;
}

export function safeJson(s: string | null | undefined): unknown {
  if (!s) return null;
  try { return JSON.parse(s); } catch { return s; }
}

export function prettyJson(s: string | null | undefined): string {
  if (!s) return '';
  try {
    return JSON.stringify(JSON.parse(s), null, 2);
  } catch {
    return s;
  }
}
