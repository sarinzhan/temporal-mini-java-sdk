import { useState } from 'react';
import { Ban, Play, Send, MoreHorizontal } from 'lucide-react';
import { Button } from '@/components/ui/Button';
import { Dialog, DialogContent, DialogTrigger } from '@/components/ui/Dialog';
import { Input } from '@/components/ui/Input';
import {
  DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuTrigger,
} from '@/components/ui/DropdownMenu';
import { useWorkflowAction } from '@/hooks/useWorkflows';
import type { WorkflowDetail } from '@/types';

export function AdminActions({ workflow }: { workflow: WorkflowDetail }) {
  const actions = useWorkflowAction(workflow.id);
  const canCancel = workflow.status === 'RUNNING' || workflow.status === 'PENDING';
  const canResume = workflow.status === 'CANCELLED';
  const canSignal = workflow.status === 'RUNNING' || workflow.status === 'PENDING';

  return (
    <div className="flex items-center gap-2">
      {canCancel && (
        <Button variant="danger" size="md" onClick={() => actions.cancel.mutate()} disabled={actions.cancel.isPending}>
          <Ban className="h-3.5 w-3.5" />
          Cancel
        </Button>
      )}
      {canResume && (
        <Button variant="primary" size="md" onClick={() => actions.resume.mutate()} disabled={actions.resume.isPending}>
          <Play className="h-3.5 w-3.5" />
          Resume
        </Button>
      )}
      {canSignal && <SignalDialog onSubmit={(b) => actions.signal.mutate(b)} pending={actions.signal.isPending} />}

      <DropdownMenu>
        <DropdownMenuTrigger asChild>
          <Button variant="ghost" size="icon"><MoreHorizontal className="h-4 w-4" /></Button>
        </DropdownMenuTrigger>
        <DropdownMenuContent align="end">
          <DropdownMenuItem
            disabled={!(workflow.status === 'FAILED' || workflow.status === 'CANCELLED')}
            onSelect={() => actions.resume.mutate()}
          >
            <Play className="h-3.5 w-3.5" /> Force resume
          </DropdownMenuItem>
        </DropdownMenuContent>
      </DropdownMenu>
    </div>
  );
}

function SignalDialog({ onSubmit, pending }: { onSubmit: (b: { signalName: string; payload: string }) => void; pending: boolean }) {
  const [open, setOpen] = useState(false);
  const [name, setName] = useState('');
  const [payload, setPayload] = useState('');
  const submit = () => {
    if (!name) return;
    onSubmit({ signalName: name, payload });
    setOpen(false);
    setName('');
    setPayload('');
  };
  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogTrigger asChild>
        <Button variant="secondary" size="md"><Send className="h-3.5 w-3.5" /> Signal</Button>
      </DialogTrigger>
      <DialogContent title="Send signal" description="Deliver a signal to the running workflow">
        <div className="space-y-3 p-5">
          <div>
            <div className="mb-1.5 text-xs font-semibold text-fg-muted">Signal name</div>
            <Input value={name} onChange={(e) => setName(e.target.value)} placeholder="e.g. cancel-job" />
          </div>
          <div>
            <div className="mb-1.5 text-xs font-semibold text-fg-muted">Payload (JSON)</div>
            <textarea
              value={payload}
              onChange={(e) => setPayload(e.target.value)}
              rows={6}
              placeholder='{"reason": "manual"}'
              className="w-full rounded-md border border-border bg-bg-elevated px-2.5 py-2 font-mono text-xs text-fg focus:outline-none focus:ring-2 focus:ring-accent"
            />
          </div>
          <div className="flex justify-end gap-2 pt-2">
            <Button variant="ghost" size="md" onClick={() => setOpen(false)}>Cancel</Button>
            <Button variant="primary" size="md" disabled={!name || pending} onClick={submit}>Send</Button>
          </div>
        </div>
      </DialogContent>
    </Dialog>
  );
}
