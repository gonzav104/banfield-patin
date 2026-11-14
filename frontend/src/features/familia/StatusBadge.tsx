import { cn } from '@/lib/utils';

export type BadgeTone = 'positive' | 'neutral' | 'warning';

const tones: Record<BadgeTone, string> = {
  positive: 'border-brand-700 bg-brand-50 text-brand-900',
  neutral: 'border-neutral-400 bg-neutral-100 text-neutral-800',
  warning: 'border-amber-600 bg-amber-50 text-amber-900',
};

/** A status as TEXT (never only colour), optionally preceded by a screen-reader-only label ("Estado: "). */
export function StatusBadge({ tone, srLabel, children }: { tone: BadgeTone; srLabel?: string; children: string }) {
  return (
    <span className={cn('inline-block shrink-0 rounded-full border px-2.5 py-0.5 text-xs font-bold', tones[tone])}>
      {srLabel ? <span className="sr-only">{srLabel}</span> : null}
      {children}
    </span>
  );
}
