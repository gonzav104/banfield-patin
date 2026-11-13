import { useEffect, useRef } from 'react';
import { Link, useParams } from 'react-router-dom';
import { Alert } from '@/components/ui/alert';
import { SESSION_EXPIRED_MESSAGE } from '@/features/auth/messages';
import { useFamilyAccount } from './account';
import { AthleteNotFoundNotice, ErrorBlock, LoadingBlock } from './FamilyStates';
import { useAthleteFichaState } from './ficha-state';
import { identityKey, type FamilyIdentity } from './identity';
import { SCHOOL_VS_SPORT_NOTE, SHEET_LOADING } from './messages';
import { Row, SeasonCard } from './SeasonCard';
import { StatusBadge } from './StatusBadge';

function AthleteSheet({ identity, athleteId }: { identity: FamilyIdentity | null; athleteId: string | undefined }) {
  const state = useAthleteFichaState(identity, athleteId);
  const heading = useRef<HTMLHeadingElement>(null);
  const ready = state.status === 'ready';

  useEffect(() => {
    if (ready) heading.current?.focus(); // a new screen: keyboard and screen-reader users land on its title
  }, [ready]);

  if (state.status === 'no-session') return null; // the route guard sends anyone without a FAMILIA session to login

  return (
    <div className="space-y-6">
      <Link to="/familia/deportistas" className="inline-flex min-h-11 items-center gap-1 text-sm font-semibold text-brand-700 hover:underline">
        <span aria-hidden="true">←</span> Volver a deportistas
      </Link>

      {state.status === 'ready' ? (
        <>
          <section>
            <p className="text-sm font-semibold text-brand-700">Ficha del deportista</p>
            <h1 ref={heading} tabIndex={-1} className="mt-1 break-words text-3xl font-extrabold tracking-tight outline-none">
              {state.ficha.nombre} {state.ficha.apellido}
            </h1>
          </section>
          <section aria-labelledby="athlete-data-title" className="rounded-2xl border border-line bg-white p-5">
            <h2 id="athlete-data-title" className="mb-4 text-lg font-bold">
              Datos del deportista
            </h2>
            <dl>
              <Row term="Edad">{state.ageText}</Row>
              <Row term="Estado en la escuela">
                <StatusBadge tone={state.ficha.activo ? 'positive' : 'neutral'}>{state.ficha.activo ? 'Activo' : 'Inactivo'}</StatusBadge>
              </Row>
            </dl>
          </section>
          <SeasonCard season={state.season} />
          <p className="text-sm leading-relaxed text-muted">{SCHOOL_VS_SPORT_NOTE}</p>
        </>
      ) : (
        <>
          <h1 className="text-3xl font-extrabold tracking-tight">Ficha del deportista</h1>
          {state.status === 'loading' ? <LoadingBlock label={SHEET_LOADING} /> : null}
          {state.status === 'invalid-id' || state.status === 'not-found' ? <AthleteNotFoundNotice /> : null}
          {state.status === 'session-expired' ? <Alert tone="warning">{SESSION_EXPIRED_MESSAGE}</Alert> : null}
          {state.status === 'error' ? <ErrorBlock error={state.error} onRetry={state.retry} /> : null}
        </>
      )}
    </div>
  );
}

/**
 * Athlete sheet (/familia/deportistas/:deportistaId), read-only. It is mounted with a key made of the identity AND the
 * athlete, so another user, another family or another athlete always starts from a clean screen (no state, no focus and
 * nothing of the previous one). All data and authorization come from the typed state of `useAthleteFichaState`.
 */
export function AthleteSheetPage() {
  const { deportistaId } = useParams();
  const account = useFamilyAccount();
  const identity = account?.identity ?? null;
  return <AthleteSheet key={`${identity ? identityKey(identity) : 'sin-sesion'}|${deportistaId ?? ''}`} identity={identity} athleteId={deportistaId} />;
}
