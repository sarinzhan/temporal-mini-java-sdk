import { forwardRef, type InputHTMLAttributes } from 'react';
import { cn } from '@/lib/cn';

export const Input = forwardRef<HTMLInputElement, InputHTMLAttributes<HTMLInputElement>>(
  ({ className, ...rest }, ref) => (
    <input
      ref={ref}
      className={cn(
        'h-8 w-full rounded-md border border-border bg-bg-elevated px-2.5 text-sm text-fg',
        'placeholder:text-fg-faint',
        'focus:outline-none focus:ring-2 focus:ring-accent focus:border-accent',
        'disabled:opacity-50',
        className,
      )}
      {...rest}
    />
  ),
);
Input.displayName = 'Input';
