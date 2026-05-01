import { request } from './client';
import type { AuthUser, LoginRequest } from '../types/auth';

export const authApi = {
  me:     () => request<AuthUser>('/auth/me'),
  login:  (body: LoginRequest) => request<AuthUser>('/auth/login', { method: 'POST', body: JSON.stringify(body) }),
  logout: () => request<{ status: string }>('/auth/logout', { method: 'POST' }),
};
