import { cn } from '@/lib/utils';
import { formatAge } from './age';
import type { Athlete } from './schemas';

function initials(athlete: Athlete): string {
  return `${athlete.nombre.trim().charAt(0)}${athlete.apellido.trim().charAt(0)}`.toUpperCase();
}

/**
 * One linked athlete: name, the age computed by the server and the school status exactly as the backend reports it (`activo`). The status is
 * text, not only colour. It never says "inscripto" or similar: it is not an enrolment or a season status.
 */
export function AthleteCard({ athlete }: { athlete: Athlete }) {
  return (
    <li className="flex items-center gap-3 rounded-2xl border border-line bg-white p-4">
      <span
        aria-hidden="true"
        className="flex size-11 shrink-0 items-center justify-center rounded-full bg-brand-50 text-sm font-extrabold text-brand-800"
      >
        {initials(athlete)}
      </span>
      <div className="min-w-0 flex-1">
        <p className="break-words font-semibold leading-snug">
          {athlete.nombre} {athlete.apellido}
        </p>
        <p className="text-sm text-muted">{formatAge(athlete.edad)}</p>
      </div>
      <p
        className={cn(
          'shrink-0 rounded-full border px-2.5 py-0.5 text-xs font-bold',
          athlete.activo ? 'border-brand-700 bg-brand-50 text-brand-900' : 'border-neutral-400 bg-neutral-100 text-neutral-800',
        )}
      >
        <span className="sr-only">Estado: </span>
        {athlete.activo ? 'Activo' : 'Inactivo'}
      </p>
    </li>
  );
}
