import type { AggregatedState } from '../types/cluster';
import { baseUrl } from '../utils/baseUrl';
import { ApiError } from './client';

export const clusterApi = {
  getState: async (): Promise<AggregatedState> => {
    // /ui/state is served from the same origin, outside /temporal-mini/api prefix
    const base = baseUrl.get().replace(/\/temporal-mini\/api.*/, '');
    const res = await fetch(base + '/ui/state', { credentials: 'include' });
    if (!res.ok) throw new ApiError(res.status, res.statusText);
    return res.json();
  },
};
