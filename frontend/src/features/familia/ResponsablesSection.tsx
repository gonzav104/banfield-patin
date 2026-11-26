import { TIPO_DISCLAIMER, tipoLabel } from '@/features/responsables/tipo';
import { ErrorBlock, LoadingBlock } from './FamilyStates';
import { useAthleteResponsables } from './hooks';
import type { FamilyIdentity } from './identity';
import { RESPONSABLES_EMPTY, RESPONSABLES_INTRO, RESPONSABLES_LOADING, RESPONSABLES_TITLE } from './messages';

/**
 * "Responsables" in the athlete sheet: the tutors of THIS family that the club declared as responsible for the athlete. Read-only:
 * the family cannot edit, see history or see responsables of another family, and the server sends nothing else. The type is a declared
 * datum, not a legal accreditation, and an unset type reads "Sin declarar", which is not the same as "Sin determinar".
 */
export function ResponsablesSection({ identity, athleteId }: { identity: FamilyIdentity; athleteId: string }) {
  const query = useAthleteResponsables(identity, athleteId);
  // A failed refresh (for example the 404 after the link was revoked) must not leave the previous list on screen: only the error is drawn.
  const rows = query.isError ? undefined : query.data;
  return (
    <section aria-labelledby="responsables-title" className="space-y-4 rounded-2xl border border-line bg-white p-5">
      <div className="space-y-2">
        <h2 id="responsables-title" className="text-lg font-bold">
          {RESPONSABLES_TITLE}
        </h2>
        <p className="text-sm leading-relaxed text-muted">{RESPONSABLES_INTRO}</p>
      </div>
      {query.isPending ? <LoadingBlock label={RESPONSABLES_LOADING} /> : null}
      {query.isError ? <ErrorBlock error={query.error} onRetry={() => void query.refetch()} /> : null}
      {rows && rows.length === 0 ? <p className="text-sm text-muted">{RESPONSABLES_EMPTY}</p> : null}
      {rows && rows.length > 0 ? (
        <>
          <ul className="divide-y divide-line">
            {rows.map((r) => (
              <li key={r.tutorId} className="flex flex-wrap items-baseline justify-between gap-x-4 gap-y-1 py-3 first:pt-0 last:pb-0">
                <span className="break-words font-semibold">
                  {r.nombre} {r.apellido}
                </span>
                <span className="text-sm text-muted">{tipoLabel(r.tipo)}</span>
              </li>
            ))}
          </ul>
          <p className="text-xs leading-relaxed text-muted">{TIPO_DISCLAIMER}</p>
        </>
      ) : null}
    </section>
  );
}
