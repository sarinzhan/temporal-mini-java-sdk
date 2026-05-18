import * as DialogPrimitive from '@radix-ui/react-dialog';
import { X } from 'lucide-react';
import type { ReactNode } from 'react';
import { cn } from '@/lib/cn';

export const Dialog = DialogPrimitive.Root;
export const DialogTrigger = DialogPrimitive.Trigger;

interface DialogContentProps {
  children: ReactNode;
  title?: ReactNode;
  description?: ReactNode;
  className?: string;
  side?: 'center' | 'right';
}

export function DialogContent({ children, title, description, className, side = 'center' }: DialogContentProps) {
  return (
    <DialogPrimitive.Portal>
      <DialogPrimitive.Overlay className="fixed inset-0 z-40 bg-black/40 backdrop-blur-[1px] data-[state=open]:animate-in data-[state=open]:fade-in-0" />
      <DialogPrimitive.Content
        className={cn(
          'fixed z-50 border border-border bg-bg-elevated shadow-lg outline-none',
          side === 'center'
            ? 'left-1/2 top-1/2 w-[min(540px,92vw)] -translate-x-1/2 -translate-y-1/2 rounded-lg'
            : 'right-0 top-0 h-full w-[min(640px,96vw)] border-l',
          className,
        )}
      >
        <div className="flex items-start justify-between gap-4 border-b border-border px-5 py-3">
          <div className="min-w-0">
            {title && <DialogPrimitive.Title className="text-sm font-semibold text-fg">{title}</DialogPrimitive.Title>}
            {description && <DialogPrimitive.Description className="mt-0.5 text-xs text-fg-muted">{description}</DialogPrimitive.Description>}
          </div>
          <DialogPrimitive.Close className="rounded-md p-1 text-fg-muted hover:bg-bg-subtle hover:text-fg">
            <X className="h-4 w-4" />
          </DialogPrimitive.Close>
        </div>
        <div className="overflow-y-auto" style={{ maxHeight: side === 'right' ? 'calc(100vh - 53px)' : '70vh' }}>
          {children}
        </div>
      </DialogPrimitive.Content>
    </DialogPrimitive.Portal>
  );
}
