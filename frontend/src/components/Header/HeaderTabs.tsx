import { Tab, Tabs } from '@mui/material';
import { useLocation, useNavigate } from 'react-router-dom';

const ROUTES = [
  { path: '/workflows', label: 'Workflows' },
  { path: '/metrics',   label: 'Metrics' },
] as const;

export function HeaderTabs() {
  const location = useLocation();
  const navigate = useNavigate();

  // Match the longest prefix so /workflows/:id keeps the Workflows tab active.
  const active = ROUTES.find((r) => location.pathname.startsWith(r.path))?.path ?? false;

  return (
    <Tabs
      value={active}
      onChange={(_, v: string) => navigate(v)}
      textColor="inherit"
      indicatorColor="primary"
      sx={{ minHeight: 40 }}
    >
      {ROUTES.map((r) => (
        <Tab
          key={r.path}
          value={r.path}
          label={r.label}
          sx={{ minHeight: 40, textTransform: 'none', fontWeight: 600 }}
        />
      ))}
    </Tabs>
  );
}
