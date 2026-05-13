import { useEffect, useMemo, useState } from 'react';
import type { RowSelectionState, SortingState } from '@tanstack/react-table';
import { Container, Stack } from '@mui/material';
import { Header } from '../components/Header/Header';
import { StatsCards } from '../components/StatsCards/StatsCards';
import { PoolGauge } from '../components/PoolGauge/PoolGauge';
import { WorkflowTable } from '../components/WorkflowTable/WorkflowTable';
import { BulkActionBar } from '../components/BulkActionBar/BulkActionBar';
import { useWorkflows } from '../hooks/useWorkflows';
import { useStats } from '../hooks/useStats';
import { useLastActivities } from '../hooks/useLastActivities';
import { useRuntime } from '../hooks/useRuntime';

const PAGE_SIZE_OPTIONS = [10, 20, 50, 100];
const PAGE_SIZE_KEY = 'temporal-mini.pageSize';
const SORT_KEY      = 'temporal-mini.sort';

export function WorkflowsPage() {
  const [states, setStates] = useState<string[]>([]);
  const [page, setPage]     = useState(0);
  const [pageSize, setPageSize] = useState<number>(() => readInitialPageSize());
  const [sorting, setSorting]   = useState<SortingState>(() => readInitialSort());
  const [rowSelection, setRowSelection] = useState<RowSelectionState>({});

  useEffect(() => {
    localStorage.setItem(PAGE_SIZE_KEY, String(pageSize));
  }, [pageSize]);
  useEffect(() => {
    localStorage.setItem(SORT_KEY, JSON.stringify(sorting));
  }, [sorting]);

  const sortParam = sorting.length > 0
    ? `${sorting[0].id},${sorting[0].desc ? 'desc' : 'asc'}`
    : 'id,desc';

  const selectedIds = useMemo(
    () => Object.entries(rowSelection).filter(([, v]) => v).map(([k]) => Number(k)),
    [rowSelection],
  );

  const stats = useStats();

  const list = useWorkflows({ page, size: pageSize, states, sort: sortParam });
  const ids = list.data?.content?.map((w) => w.id) ?? [];
  const lastActs = useLastActivities(ids);
  const runtime = useRuntime();

  function handleStatesChange(next: string[]) {
    setStates(next);
    setPage(0);
  }

  function handlePageSizeChange(size: number) {
    setPageSize(size);
    setPage(0);
  }

  return (
    <>
      <Header />
      <Container maxWidth="xl" sx={{ py: 3 }}>
        <Stack spacing={2}>
          <PoolGauge />
          <StatsCards stats={stats.data} selected={states} onChange={handleStatesChange} />
          {selectedIds.length > 0 && (
            <BulkActionBar selectedIds={selectedIds} onClear={() => setRowSelection({})} />
          )}
          <WorkflowTable
            data={list.data}
            lastActivities={lastActs.data ?? {}}
            runtime={runtime.data ?? {}}
            page={page}
            pageSize={pageSize}
            pageSizeOptions={PAGE_SIZE_OPTIONS}
            sorting={sorting}
            rowSelection={rowSelection}
            onPageChange={setPage}
            onPageSizeChange={handlePageSizeChange}
            onSortingChange={(s) => { setSorting(s); setPage(0); }}
            onRowSelectionChange={setRowSelection}
          />
        </Stack>
      </Container>
    </>
  );
}

function readInitialPageSize(): number {
  const raw = localStorage.getItem(PAGE_SIZE_KEY);
  const n = raw == null ? NaN : Number(raw);
  return PAGE_SIZE_OPTIONS.includes(n) ? n : 20;
}

function readInitialSort(): SortingState {
  try {
    const raw = localStorage.getItem(SORT_KEY);
    if (!raw) return [{ id: 'id', desc: true }];
    const parsed = JSON.parse(raw);
    if (Array.isArray(parsed)) return parsed as SortingState;
  } catch { /* ignore */ }
  return [{ id: 'id', desc: true }];
}

