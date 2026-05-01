import { request } from './client';
import type { PoolStats } from '../types/pool';

export const poolApi = {
  get: () => request<PoolStats>('/pool'),
};
