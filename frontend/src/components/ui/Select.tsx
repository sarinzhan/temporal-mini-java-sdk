import * as SelectPrimitive from '@radix-ui/react-select';
import { Check, ChevronDown } from 'lucide-react';
import { forwardRef, type ComponentPropsWithoutRef, type ElementRef } from 'react';
import { cn } from '@/lib/cn';

export const Select = SelectPrimitive.Root;
export const SelectValue = SelectPrimitive.Value;

export const SelectTrigger = forwardRef<
  ElementRef<typeof SelectPrimitive.Trigger>,
  ComponentPropsWithoutRef<typeof SelectPrimitive.Trigger>
>(({ className, children, ...rest }, ref) => (
  <SelectPrimitive.Trigger
    ref={ref}
    className={cn(
      'inline-flex h-8 items-center justify-between gap-1.5 rounded-md border border-border bg-bg-elevated px-2.5 text-sm text-fg',
      'focus:outline-none focus:ring-2 focus:ring-accent focus:border-accent',
      'disabled:opacity-50',
      className,
    )}
    {...rest}
  >
    {children}
    <SelectPrimitive.Icon><ChevronDown className="h-3.5 w-3.5 text-fg-muted" /></SelectPrimitive.Icon>
  </SelectPrimitive.Trigger>
));
SelectTrigger.displayName = 'SelectTrigger';

export const SelectContent = forwardRef<
  ElementRef<typeof SelectPrimitive.Content>,
  ComponentPropsWithoutRef<typeof SelectPrimitive.Content>
>(({ className, children, position = 'popper', ...rest }, ref) => (
  <SelectPrimitive.Portal>
    <SelectPrimitive.Content
      ref={ref}
      position={position}
      className={cn(
        'z-50 overflow-hidden rounded-md border border-border bg-bg-elevated shadow-md',
        'data-[state=open]:animate-in data-[state=open]:fade-in-0',
        className,
      )}
      {...rest}
    >
      <SelectPrimitive.Viewport className="p-1 min-w-[var(--radix-select-trigger-width)]">
        {children}
      </SelectPrimitive.Viewport>
    </SelectPrimitive.Content>
  </SelectPrimitive.Portal>
));
SelectContent.displayName = 'SelectContent';

export const SelectItem = forwardRef<
  ElementRef<typeof SelectPrimitive.Item>,
  ComponentPropsWithoutRef<typeof SelectPrimitive.Item>
>(({ className, children, ...rest }, ref) => (
  <SelectPrimitive.Item
    ref={ref}
    className={cn(
      'relative flex h-7 cursor-pointer select-none items-center rounded-sm pl-7 pr-2 text-sm text-fg',
      'data-[highlighted]:bg-bg-subtle data-[highlighted]:outline-none',
      'data-[state=checked]:font-medium',
      className,
    )}
    {...rest}
  >
    <span className="absolute left-2 flex h-3.5 w-3.5 items-center justify-center">
      <SelectPrimitive.ItemIndicator><Check className="h-3.5 w-3.5" /></SelectPrimitive.ItemIndicator>
    </span>
    <SelectPrimitive.ItemText>{children}</SelectPrimitive.ItemText>
  </SelectPrimitive.Item>
));
SelectItem.displayName = 'SelectItem';
