import { useState } from 'react';
import {
  Box,
  Divider,
  IconButton,
  Menu,
  MenuItem,
  Tooltip,
  Typography,
} from '@mui/material';
import StorageIcon from '@mui/icons-material/Storage';
import SettingsIcon from '@mui/icons-material/Settings';
import { useBackend } from '../../contexts/BackendContext';
import { BackendsManageDialog } from './BackendsManageDialog';

/**
 * Compact dropdown that lists configured backends and reveals a "Manage…" entry
 * which opens the full add/remove dialog. Stays out of the way until clicked.
 */
export function BackendSelect() {
  const { backends, selected, select } = useBackend();
  const [anchor, setAnchor] = useState<HTMLElement | null>(null);
  const [manageOpen, setManageOpen] = useState(false);

  return (
    <>
      <Tooltip title={`API base: ${selected.baseUrl}`}>
        <Box
          onClick={(e) => setAnchor(e.currentTarget)}
          sx={{
            display: 'flex',
            alignItems: 'center',
            gap: 1,
            px: 1.25,
            py: 0.5,
            borderRadius: 1,
            cursor: 'pointer',
            border: 1,
            borderColor: 'rgba(255,255,255,.4)',
            color: '#fff',
            '&:hover': { borderColor: '#fff', bgcolor: 'rgba(255,255,255,.05)' },
          }}
        >
          <StorageIcon fontSize="small" />
          <Typography variant="body2" sx={{ fontWeight: 600 }}>{selected.name}</Typography>
        </Box>
      </Tooltip>

      <Menu anchorEl={anchor} open={!!anchor} onClose={() => setAnchor(null)}>
        {backends.map((b) => (
          <MenuItem
            key={b.id}
            selected={b.id === selected.id}
            onClick={() => { select(b.id); setAnchor(null); }}
          >
            <Box>
              <Typography variant="body2" sx={{ fontWeight: 600 }}>{b.name}</Typography>
              <Typography variant="caption" sx={{ color: 'text.disabled' }}>{b.baseUrl}</Typography>
            </Box>
          </MenuItem>
        ))}
        <Divider />
        <MenuItem onClick={() => { setManageOpen(true); setAnchor(null); }}>
          <IconButton size="small" sx={{ mr: 1 }}><SettingsIcon fontSize="small" /></IconButton>
          Manage backends…
        </MenuItem>
      </Menu>

      <BackendsManageDialog open={manageOpen} onClose={() => setManageOpen(false)} />
    </>
  );
}
