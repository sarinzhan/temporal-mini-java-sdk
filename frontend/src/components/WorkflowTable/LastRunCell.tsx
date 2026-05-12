import { RelativeTime } from '../RelativeTime/RelativeTime';
import type { Workflow } from '../../types/workflow';
import type { LastActivity } from '../../types/activity';

interface Props {
  workflow: Workflow;
  lastAttemptAt: LastActivity['lastAttemptAt'];
}

export function LastRunCell({ workflow, lastAttemptAt }: Props) {
  return <RelativeTime value={lastAttemptAt ?? workflow.startedAt} />;
}
