export interface PoolStats {
  active: number;
  free: number;
  poolSize: number;
  corePoolSize: number;
  maxPoolSize: number;
  queue: number;
  queueCapacity: number;
}
