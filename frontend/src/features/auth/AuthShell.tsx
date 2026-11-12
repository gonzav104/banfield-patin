import type { ReactNode } from 'react';
import { BrandMark, BrandName } from '@/components/brand';
import { cn } from '@/lib/utils';

interface AuthShellProps {
  variant: 'family' | 'admin';
  title: string;
  description?: string;
  children: ReactNode;
  footer?: ReactNode;
}

/** Shared frame for every pre-session screen. Family is light and mobile-first; admin is dark and sober. */
export function AuthShell({ variant, title, description, children, footer }: AuthShellProps) {
  const admin = variant === 'admin';
  return (
    <div className={cn('flex min-h-dvh flex-col', admin ? 'bg-ink' : 'bg-brand-800')}>
      <header className="mx-auto flex w-full max-w-md items-center gap-3 px-4 pt-6 sm:pt-10">
        <BrandMark />
        <BrandName tone="light" />
        {admin ? (
          <span className="ml-auto rounded-full border border-brand-500 px-3 py-1 text-xs font-bold uppercase tracking-wider text-brand-200">
            Administración
          </span>
        ) : null}
      </header>
      <main className="mx-auto flex w-full max-w-md flex-1 flex-col px-4 py-6 sm:justify-center sm:py-10">
        <div className="rounded-2xl bg-white p-6 shadow-xl sm:p-8">
          <h1 className="text-2xl font-extrabold tracking-tight">{title}</h1>
          {description ? <p className="mt-2 text-sm leading-relaxed text-muted">{description}</p> : null}
          <div className="mt-6">{children}</div>
        </div>
        {footer ? <div className="mt-5 text-center text-sm text-white/80">{footer}</div> : null}
      </main>
    </div>
  );
}
