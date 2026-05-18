import { BarChart3 } from 'lucide-react';

export function MetricsPage() {
  return (
    <div className="mx-auto flex max-w-7xl flex-col items-center justify-center px-4 py-24 text-center">
      <BarChart3 className="mb-3 h-8 w-8 text-fg-faint" />
      <h2 className="text-lg font-semibold">Metrics</h2>
      <p className="mt-1 max-w-md text-sm text-fg-muted">
        Coming soon. Plan: workflow throughput, latency percentiles, failure clustering, worker pool saturation.
      </p>
    </div>
  );
}
