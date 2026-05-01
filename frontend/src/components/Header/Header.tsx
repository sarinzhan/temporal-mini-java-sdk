import { AppBar, Box, Button, Toolbar, Typography } from '@mui/material';
import { useAuth } from '../../contexts/AuthContext';
import { RefreshIntervalSelect } from './RefreshIntervalSelect';

export function Header() {
  const { user, logout } = useAuth();
  return (
    <AppBar position="sticky" color="secondary" elevation={1}>
      <Toolbar variant="dense" sx={{ gap: 2 }}>
        <Typography variant="h6" sx={{ fontWeight: 700, letterSpacing: -0.3 }}>
          ⚡ temporal<Box component="span" sx={{ color: 'primary.main' }}>-mini</Box>
        </Typography>
        <Box sx={{ flex: 1 }} />
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
