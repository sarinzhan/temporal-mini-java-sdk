import { useMemo, useState } from 'react';
import { Container, Stack } from '@mui/material';
import { Header } from '../components/Header/Header';
import { StatsCards } from '../components/StatsCards/StatsCards';
import { PoolGauge } from '../components/PoolGauge/PoolGauge';
import { WorkflowTable } from '../components/WorkflowTable/WorkflowTable';
import { useWorkflows } from '../hooks/useWorkflows';
import { useStats } from '../hooks/useStats';
import { useRuntime } from '../hooks/useRuntime';
import { useLastActivities } from '../hooks/useLastActivities';
import { workflowsApi } from '../api/workflows';
import { useQuery } from '@tanstack/react-query';
import { useRefreshInterval } from '../hooks/useRefreshInterval';

const PAGE_SIZE = 20;

export function WorkflowsPage() {
  const [states, setStates] = useState<string[]>([]);
  const [page, setPage]     = useState(0);

  const stats   = useStats();
  const runtime = useRuntime();
  // RUNNING is a runtime view (not a DB state) — when it's the only filter selected,
  // resolve via /runtime and fetch each workflow individually, mirroring the legacy UI.
  const runningOnly = states.length === 1 && states[0] === 'RUNNING';
  const dbStates    = states.filter((s) => s !== 'RUNNING');

  const list    = useWorkflows({ page, size: PAGE_SIZE, states: dbStates });
  const runningList = useRunningWorkflows(runningOnly && runtime.data ? Object.keys(runtime.data).map(Number) : []);

  const effective = runningOnly ? runningList : list;
  const ids = effective.data?.content?.map((w) => w.id) ?? [];
  const lastActs = useLastActivities(ids);

  function handleStatesChange(next: string[]) {
    setStates(next);
    setPage(0);
  }

  return (
    <>
      <Header />
      <Container maxWidth="xl" sx={{ py: 3 }}>
        <Stack spacing={2}>
          <PoolGauge />
          <StatsCards stats={stats.data} selected={states} onChange={handleStatesChange} />
          <WorkflowTable
            data={effective.data}
            runtime={runtime.data ?? {}}
            lastActivities={lastActs.data ?? {}}
            page={page}
            pageSize={PAGE_SIZE}
            onPageChange={setPage}
          />
        </Stack>
      </Container>
    </>
  );
}

/**
 * Special-case query for the RUNNING-only filter: hits /runtime to learn the ids
 * then fetches each workflow individually. The set is small in practice — it's
 * bounded by the size of the executor pool.
 */
function useRunningWorkflows(ids: number[]) {
  const refetchInterval = useRefreshInterval();
  const stable = useMemo(() => [...ids].sort((a, b) => a - b), [ids]);
  return useQuery({
    queryKey: ['running-workflows', stable],
    queryFn: async () => {
      const fetched = await Promise.all(
        stable.map((id) => workflowsApi.one(id).catch(() => null)),
      );
      const content = fetched
        .filter((w): w is NonNullable<typeof w> => w != null)
        .sort((a, b) => b.id - a.id);
      return {
        content,
        number: 0,
        totalPages: 1,
        totalElements: content.length,
      };
    },
    enabled: ids.length > 0,
    refetchInterval,
  });
}
