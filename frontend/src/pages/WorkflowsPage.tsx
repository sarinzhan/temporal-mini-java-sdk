import { useMemo, useState } from 'react';
import { QuickFilters } from '@/components/workflows/QuickFilters';
import { AdvancedFilters, type AdvancedFilterState } from '@/components/workflows/AdvancedFilters';
import { WorkflowTable } from '@/components/workflows/WorkflowTable';
import { useWorkflows } from '@/hooks/useWorkflows';
import type { WorkflowSearchParams } from '@/types';

type Quick = 'all' | 'running' | 'today' | 'last-hour';

export function WorkflowsPage() {
  const [quick, setQuick] = useState<Quick>('all');
  const [filters, setFilters] = useState<AdvancedFilterState>({ statuses: [] });
  const [page, setPage] = useState(0);
  const size = 25;

  const params = useMemo<WorkflowSearchParams>(() => ({
    quick,
    status: filters.statuses.length ? filters.statuses : undefined,
    type: filters.type,
    id: filters.id,
    from: filters.from,
    to: filters.to,
    page,
    size,
  }), [quick, filters, page]);

  const { data, isLoading } = useWorkflows(params);

  return (
    <div className="mx-auto max-w-7xl px-4 py-6">
      <div className="mb-4 flex items-center justify-between gap-3">
        <h1 className="text-xl font-semibold tracking-tight">Workflows</h1>
      </div>

      <div className="mb-4 flex flex-wrap items-center gap-2">
        <QuickFilters value={quick} onChange={(q) => { setQuick(q); setPage(0); }} />
        <AdvancedFilters value={filters} onChange={(f) => { setFilters(f); setPage(0); }} />
      </div>

      <WorkflowTable
        data={data}
        loading={isLoading}
        page={page}
        size={size}
        onPageChange={setPage}
      />
    </div>
  );
}
