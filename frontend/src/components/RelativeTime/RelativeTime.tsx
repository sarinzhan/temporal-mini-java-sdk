import { useEffect, useState } from 'react';
import { Tooltip, Typography } from '@mui/material';
import { fmtDate, fmtElapsed, toDate, type DateLike } from '../../utils/format';

interface Props {
  value: DateLike;
  /** "now" → "just now"; otherwise "5m ago". Defaults to "ago". */
  mode?: 'ago' | 'in';
  /** Tooltip shows the absolute timestamp on hover. Defaults to true. */
  showAbsolute?: boolean;
}

/**
 * Renders a value like "5m ago" that auto-updates every 30 seconds without
 * re-fetching the underlying data. Hover reveals the absolute timestamp.
 */
export function RelativeTime({ value, mode = 'ago', showAbsolute = true }: Props) {
  const [, tick] = useState(0);
  useEffect(() => {
    const id = setInterval(() => tick((n) => n + 1), 30_000);
    return () => clearInterval(id);
  }, []);

  const d = toDate(value);
  if (!d) return <Typography component="span" variant="caption" sx={{ color: 'text.disabled' }}>—</Typography>;

  const ms = Date.now() - d.getTime();
  const text = ms < 5_000
    ? 'just now'
    : mode === 'ago'
      ? `${fmtElapsed(ms)} ago`
      : `in ${fmtElapsed(-ms)}`;

  const span = (
    <Typography component="span" variant="caption" sx={{ color: 'text.secondary' }}>
      {text}
    </Typography>
  );

  return showAbsolute ? <Tooltip title={fmtDate(value)} arrow>{span}</Tooltip> : span;
}
