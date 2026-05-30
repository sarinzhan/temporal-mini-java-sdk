import {
  Activity, AlertCircle, BellRing, BookmarkCheck, CheckCircle2, Clock, GitBranch, Hourglass,
  Inbox, ListChecks, Pause, Play, PlusCircle, RefreshCw, Send, Sparkles, Square, Timer, XCircle, Zap,
} from 'lucide-react';
import type { EventType } from '@/types';

export const eventMeta: Record<EventType, { label: string; tone: 'success' | 'danger' | 'warn' | 'info' | 'neutral' | 'accent'; Icon: typeof Activity }> = {
  WORKFLOW_CREATED:        { label: 'Workflow Created',        tone: 'neutral', Icon: PlusCircle },
  WORKFLOW_TASK_QUEUED:    { label: 'Workflow Task Queued',    tone: 'neutral', Icon: Inbox },
  WORKFLOW_TASK_STARTED:   { label: 'Workflow Task Started',   tone: 'accent',  Icon: Play },
  WORKFLOW_TASK_COMPLETED: { label: 'Workflow Task Completed', tone: 'info',    Icon: ListChecks },
  WORKFLOW_COMPLETED:      { label: 'Workflow Completed',      tone: 'success', Icon: CheckCircle2 },
  WORKFLOW_FAILED:         { label: 'Workflow Failed',         tone: 'danger',  Icon: XCircle },
  WORKFLOW_CANCELLED:      { label: 'Workflow Cancelled',      tone: 'warn',    Icon: Square },
  ACTIVITY_SCHEDULED:      { label: 'Activity Scheduled',      tone: 'neutral', Icon: BookmarkCheck },
  ACTIVITY_STARTED:        { label: 'Activity Started',        tone: 'info',    Icon: Activity },
  ACTIVITY_COMPLETED:      { label: 'Activity Completed',      tone: 'success', Icon: CheckCircle2 },
  ACTIVITY_FAILED:         { label: 'Activity Failed',         tone: 'danger',  Icon: AlertCircle },
  ACTIVITY_RETRY_SCHEDULED:{ label: 'Activity Retry Scheduled',tone: 'warn',    Icon: RefreshCw },
  TIMER_STARTED:           { label: 'Timer Started',           tone: 'neutral', Icon: Timer },
  TIMER_FIRED:             { label: 'Timer Fired',             tone: 'info',    Icon: Hourglass },
  AWAIT_BLOCKED:           { label: 'Await Blocked',           tone: 'warn',    Icon: Pause },
  AWAIT_FIRED:             { label: 'Await Fired',             tone: 'info',    Icon: BellRing },
  SIGNAL_RECEIVED:         { label: 'Signal Received',         tone: 'neutral', Icon: Send },
  UPDATE_REQUESTED:        { label: 'Update Requested',        tone: 'accent',  Icon: Zap },
  UPDATE_COMPLETED:        { label: 'Update Completed',        tone: 'success', Icon: CheckCircle2 },
  SIDE_EFFECT_RECORDED:    { label: 'Side Effect Recorded',    tone: 'neutral', Icon: Sparkles },
  VERSION_MARKER:          { label: 'Version Marker',          tone: 'neutral', Icon: GitBranch },
};

export const pauseIconHint = Pause; // kept for re-export if needed
export const clockIconHint = Clock;
