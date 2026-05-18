import {
  Activity, AlertCircle, CheckCircle2, Clock, Pause, Play, RefreshCw, Send, Square, XCircle,
} from 'lucide-react';
import type { EventType } from '@/types';

export const eventMeta: Record<EventType, { label: string; tone: 'success' | 'danger' | 'warn' | 'info' | 'neutral' | 'accent'; Icon: typeof Activity }> = {
  WORKFLOW_STARTED:   { label: 'Workflow Started',   tone: 'accent',  Icon: Play },
  WORKFLOW_COMPLETED: { label: 'Workflow Completed', tone: 'success', Icon: CheckCircle2 },
  WORKFLOW_FAILED:    { label: 'Workflow Failed',    tone: 'danger',  Icon: XCircle },
  WORKFLOW_CANCELLED: { label: 'Workflow Cancelled', tone: 'warn',    Icon: Square },
  WORKFLOW_RESUMED:   { label: 'Workflow Resumed',   tone: 'info',    Icon: Play },
  ACTIVITY_STARTED:   { label: 'Activity Started',   tone: 'info',    Icon: Activity },
  ACTIVITY_COMPLETED: { label: 'Activity Completed', tone: 'success', Icon: CheckCircle2 },
  ACTIVITY_FAILED:    { label: 'Activity Failed',    tone: 'danger',  Icon: AlertCircle },
  ACTIVITY_RETRYING:  { label: 'Activity Retrying',  tone: 'warn',    Icon: RefreshCw },
  SIGNAL_SENT:        { label: 'Signal Sent',        tone: 'neutral', Icon: Send },
};

export const pauseIconHint = Pause; // kept for re-export if needed
export const clockIconHint = Clock;
