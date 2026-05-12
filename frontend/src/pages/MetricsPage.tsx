import { useEffect, useState } from 'react';
import { Alert, CircularProgress, Container, Stack } from '@mui/material';
import { Header } from '../components/Header/Header';
import { WindowPicker } from '../components/MetricsCharts/WindowPicker';
import { PoolQueueChart } from '../components/MetricsCharts/PoolQueueChart';
import { StateBreakdownChart } from '../components/MetricsCharts/StateBreakdownChart';
import { ThroughputChart } from '../components/MetricsCharts/ThroughputChart';
import { useMetricsHistory } from '../hooks/useMetricsHistory';
import type { WindowKey } from '../types/metric';

const STORAGE_KEY = 'temporal-mini.metrics.window';

export function MetricsPage() {
  const [windowKey, setWindowKey] = useState<WindowKey>(() => readInitial());

  useEffect(() => {
    localStorage.setItem(STORAGE_KEY, windowKey);
  }, [windowKey]);

  const { data, isLoading, error } = useMetricsHistory(windowKey);
  const samples = data?.samples ?? [];

  return (
    <>
      <Header />
      <Container maxWidth="xl" sx={{ py: 3 }}>
        <Stack spacing={2}>
          <WindowPicker value={windowKey} onChange={setWindowKey} />

          {error && <Alert severity="error">{(error as Error).message}</Alert>}
          {isLoading && samples.length === 0 && <CircularProgress />}

          <PoolQueueChart        samples={samples} />
          <StateBreakdownChart   samples={samples} />
          <ThroughputChart       samples={samples} />
        </Stack>
      </Container>
    </>
  );
}

function readInitial(): WindowKey {
  const raw = localStorage.getItem(STORAGE_KEY);
  const allowed: WindowKey[] = ['5m', '30m', '1h', '6h', '24h', '7d', '14d'];
  return (allowed as string[]).includes(raw ?? '') ? (raw as WindowKey) : '1h';
}
