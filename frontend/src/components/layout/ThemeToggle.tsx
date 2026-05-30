import { Monitor, Moon, Sun } from 'lucide-react';
import { useTheme } from '@/lib/theme';
import {
  DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuTrigger,
} from '@/components/ui/DropdownMenu';
import { Button } from '@/components/ui/Button';

export function ThemeToggle() {
  const { theme, resolved, setTheme } = useTheme();
  const Icon = theme === 'system' ? Monitor : resolved === 'dark' ? Moon : Sun;
  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <Button variant="ghost" size="icon" aria-label="Toggle theme">
          <Icon className="h-4 w-4" />
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end">
        <DropdownMenuItem onSelect={() => setTheme('light')}><Sun className="h-3.5 w-3.5" /> Light</DropdownMenuItem>
        <DropdownMenuItem onSelect={() => setTheme('dark')}><Moon className="h-3.5 w-3.5" /> Dark</DropdownMenuItem>
        <DropdownMenuItem onSelect={() => setTheme('system')}><Monitor className="h-3.5 w-3.5" /> System</DropdownMenuItem>
      </DropdownMenuContent>
    </DropdownMenu>
  );
}
