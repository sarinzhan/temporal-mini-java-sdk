import { AppBar, Box, Button, Toolbar, Typography } from '@mui/material';
import { useAuth } from '../../contexts/AuthContext';
import { RefreshIntervalSelect } from './RefreshIntervalSelect';
import { HeaderTabs } from './HeaderTabs';
import { BackendSelect } from './BackendSelect';

export function Header() {
  const { user, logout } = useAuth();
  return (
    <AppBar position="sticky" color="secondary" elevation={1}>
      <Toolbar variant="dense" sx={{ gap: 3 }}>
        <Typography variant="h6" sx={{ fontWeight: 700, letterSpacing: -0.3 }}>
          ⚡ temporal<Box component="span" sx={{ color: 'primary.main' }}>-mini</Box>
        </Typography>
        <HeaderTabs />
        <Box sx={{ flex: 1 }} />
        <BackendSelect />
        <RefreshIntervalSelect />
        {user && (
          <>
            <Typography variant="body2" sx={{ opacity: 0.8 }}>{user.username}</Typography>
            <Button color="inherit" size="small" onClick={() => { void logout(); }}>
              Logout
            </Button>
          </>
        )}
      </Toolbar>
    </AppBar>
  );
}
