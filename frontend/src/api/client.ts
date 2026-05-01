const BASE = '/temporal-mini/api';

export class ApiError extends Error {
  constructor(public status: number, message: string) {
    super(message);
    this.name = 'ApiError';
  }
}

export async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const headers: Record<string, string> = {};
  if (init?.body && !(init.body instanceof FormData)) {
    headers['Content-Type'] = 'application/json';
  }
  const res = await fetch(BASE + path, {
    credentials: 'include',
    ...init,
    headers: { ...headers, ...(init?.headers as Record<string, string>) },
  });
  if (res.status === 204) return undefined as T;
  const text = await res.text();
  const body = text ? safeJson(text) : {};
  if (!res.ok) {
    const msg = (body && typeof body === 'object' && 'error' in body && typeof body.error === 'string')
      ? body.error
      : res.statusText;
    throw new ApiError(res.status, msg);
  }
  return body as T;
}

function safeJson(text: string): unknown {
  try { return JSON.parse(text); }
  catch { return text; }
}
