import { useMemo } from 'react';
import { prettyJson } from '@/lib/format';
import { cn } from '@/lib/cn';

interface JsonViewerProps {
  value: string | null | undefined;
  className?: string;
  empty?: string;
}

export function JsonViewer({ value, className, empty = '—' }: JsonViewerProps) {
  const pretty = useMemo(() => prettyJson(value), [value]);
  if (!value) {
    return <div className={cn('text-fg-faint italic', className)}>{empty}</div>;
  }
  return (
    <pre className={cn(
      'rounded-md border border-border bg-bg-subtle px-3 py-2 font-mono text-xs text-fg',
      'overflow-x-auto whitespace-pre-wrap break-all',
      className,
    )}>{pretty}</pre>
  );
}
