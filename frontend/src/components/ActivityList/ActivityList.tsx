import { useMemo } from 'react';
import { Box, Typography } from '@mui/material';
import type { Activity } from '../../types/activity';
import { ActivityGroup } from './ActivityGroup';

interface Props {
  workflowId: number;
  activities: Activity[];
}

/**
 * Groups attempts by activity name (so retries collapse under a single header)
 * and orders attempts within a group by attempt number.
 */
export function ActivityList({ workflowId, activities }: Props) {
  const groups = useMemo(() => {
    const map = new Map<string, Activity[]>();
    for (const a of activities) {
      const arr = map.get(a.name);
      if (arr) arr.push(a);
      else map.set(a.name, [a]);
    }
    map.forEach((arr) => arr.sort((x, y) => x.attempt - y.attempt));
    return map;
  }, [activities]);

  if (groups.size === 0) {
    return (
      <Box sx={{ textAlign: 'center', py: 4, color: 'text.disabled' }}>
        <Typography>No activities recorded yet</Typography>
      </Box>
    );
  }

  return (
    <Box>
      {[...groups.entries()].map(([name, items]) => (
        <ActivityGroup key={name} workflowId={workflowId} name={name} attempts={items} />
      ))}
    </Box>
  );
}
