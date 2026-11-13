import type { HTMLAttributes } from 'react';
import { cn } from '@/lib/utils';

type Tone = 'error' | 'info' | 'warning' | 'success';

const tones: Record<Tone, string> = {
  error: 'border-red-300 bg-red-50 text-red-900',
  info: 'border-brand-200 bg-brand-50 text-brand-900',
  warning: 'border-amber-300 bg-amber-50 text-amber-950',
  success: 'border-brand-500 bg-brand-50 text-brand-900',
};

interface AlertProps extends HTMLAttributes<HTMLDivElement> {
  tone?: Tone;
}

/** Errors are announced assertively; informational notices politely. */
export function Alert({ tone = 'info', className, children, ...props }: AlertProps) {
  return (
    <div
      role={tone === 'error' ? 'alert' : 'status'}
      className={cn('rounded-lg border px-4 py-3 text-sm leading-relaxed', tones[tone], className)}
      {...props}
    >
      {children}
    </div>
  );
}
