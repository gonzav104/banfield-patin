import { cn } from '@/lib/utils';

/** The state is written, never only colored. */
export function AthleteStatusBadge({ active }: { active: boolean }) {
  return (
    <span
      className={cn(
        'inline-block rounded-full border px-2.5 py-0.5 text-xs font-bold',
        active ? 'border-brand-700 bg-brand-50 text-brand-900' : 'border-neutral-400 bg-neutral-100 text-neutral-800',
      )}
    >
      {active ? 'Activo' : 'Inactivo'}
    </span>
  );
}
