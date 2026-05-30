import { useParams } from 'react-router-dom';
import { Loader2 } from 'lucide-react';
import {
  Tabs, TabsContent, TabsList, TabsTrigger,
} from '@/components/ui/Tabs';
import {
  usePendingActivities, useWorkflow, useWorkflowEvents,
} from '@/hooks/useWorkflows';
import { DetailHeader } from '@/components/workflow-detail/DetailHeader';
import { AdminActions } from '@/components/workflow-detail/AdminActions';
import { EventHistory } from '@/components/workflow-detail/EventHistory';
import { PendingActivities } from '@/components/workflow-detail/PendingActivities';
import { Timeline } from '@/components/workflow-detail/Timeline';

export function WorkflowDetailPage() {
  const { id } = useParams<{ id: string }>();
  const wf = useWorkflow(id);
  const events = useWorkflowEvents(id);
  const pending = usePendingActivities(id);

  if (wf.isLoading) {
    return (
      <div className="flex h-[60vh] items-center justify-center text-fg-muted">
        <Loader2 className="h-5 w-5 animate-spin" />
      </div>
    );
  }
  if (wf.isError || !wf.data) {
    return <div className="mx-auto max-w-7xl px-4 py-6 text-danger">Workflow not found.</div>;
  }

  return (
    <div className="mx-auto max-w-7xl px-4 py-6">
      <DetailHeader workflow={wf.data} actions={<AdminActions workflow={wf.data} />} />

      <Tabs defaultValue="event-history" className="mt-6">
        <TabsList>
          <TabsTrigger value="timeline">Timeline</TabsTrigger>
          <TabsTrigger value="event-history">Event History</TabsTrigger>
          <TabsTrigger value="pending">Pending Activities</TabsTrigger>
        </TabsList>

        <TabsContent value="timeline" className="mt-4">
          <Timeline events={events.data ?? []} />
        </TabsContent>
        <TabsContent value="event-history" className="mt-4">
          <EventHistory workflowId={wf.data.id} events={events.data ?? []} />
        </TabsContent>
        <TabsContent value="pending" className="mt-4">
          <PendingActivities workflowId={wf.data.id} items={pending.data ?? []} />
        </TabsContent>
      </Tabs>
    </div>
  );
}
