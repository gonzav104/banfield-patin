import { Link } from 'react-router-dom';
import { formatAge } from './age';
import { DniSummaryLine } from './DniSummaryLine';
import type { FamilyIdentity } from './identity';
import type { Athlete } from './schemas';
import { StatusBadge } from './StatusBadge';

function initials(athlete: Athlete): string {
  return `${athlete.nombre.trim().charAt(0)}${athlete.apellido.trim().charAt(0)}`.toUpperCase();
}

/**
 * One linked athlete, as a link to its sheet: name, the age computed by the server and the school status exactly as the
 * backend reports it (`activo`). The status is text, not only colour. It never says "inscripto" or similar: it is not an
 * enrolment or a season status.
 */
/** With `summaryIdentity` (the dashboard) the card adds one line about the athlete's DNI (U20). */
export function AthleteCard({ athlete, summaryIdentity }: { athlete: Athlete; summaryIdentity?: FamilyIdentity }) {
  return (
    <li>
      <Link
        to={`/familia/deportistas/${athlete.id}`}
        className="flex min-h-11 items-center gap-3 rounded-2xl border border-line bg-white p-4 transition-colors hover:bg-brand-50"
      >
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
          {summaryIdentity ? <DniSummaryLine identity={summaryIdentity} athleteId={athlete.id} /> : null}
        </div>
        <StatusBadge tone={athlete.activo ? 'positive' : 'neutral'} srLabel="Estado: ">
          {athlete.activo ? 'Activo' : 'Inactivo'}
        </StatusBadge>
        <span aria-hidden="true" className="shrink-0 text-xl text-muted">
          ›
        </span>
      </Link>
    </li>
  );
}
