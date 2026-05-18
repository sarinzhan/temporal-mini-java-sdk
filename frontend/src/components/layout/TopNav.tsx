import { NavLink } from 'react-router-dom';
import { Workflow, BarChart3 } from 'lucide-react';
import { cn } from '@/lib/cn';
import { ThemeToggle } from './ThemeToggle';

function NavItem({ to, icon: Icon, label }: { to: string; icon: typeof Workflow; label: string }) {
  return (
    <NavLink
      to={to}
      end={false}
      className={({ isActive }) =>
        cn(
          'inline-flex h-9 items-center gap-1.5 border-b-2 px-3 text-sm font-medium',
          isActive
            ? 'border-fg text-fg'
            : 'border-transparent text-fg-muted hover:text-fg',
        )
      }
    >
      <Icon className="h-3.5 w-3.5" />
      {label}
    </NavLink>
  );
}

export function TopNav() {
  return (
    <header className="sticky top-0 z-30 flex h-12 items-center justify-between border-b border-border bg-bg/95 px-4 backdrop-blur">
      <div className="flex items-center gap-6">
        <div className="flex items-center gap-2 font-mono text-sm font-semibold tracking-tight">
          <span className="inline-block h-2 w-2 rounded-sm bg-fg" />
          workflow
        </div>
        <nav className="flex items-center">
          <NavItem to="/workflows" icon={Workflow} label="Workflows" />
          <NavItem to="/metrics" icon={BarChart3} label="Metrics" />
        </nav>
      </div>
      <div className="flex items-center gap-1">
        <ThemeToggle />
      </div>
    </header>
  );
}
