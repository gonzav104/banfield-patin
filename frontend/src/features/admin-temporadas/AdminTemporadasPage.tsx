import type { ReactNode } from 'react';
import { Alert } from '@/components/ui/alert';
import { Button } from '@/components/ui/button';
import { useAllSeasons } from '@/features/admin-datos-deportivos/hooks';
import type { Season } from '@/features/admin-datos-deportivos/schemas';
import { useSession } from '@/features/auth/session';
import { LoadingBlock } from '@/features/familia/FamilyStates';
import { StatusBadge } from '@/features/familia/StatusBadge';
import { ApiError } from '@/lib/api-error';
import { CreateSeasonForm } from './CreateSeasonForm';
import {
  ACTIVE_HEADING, ACTIVE_LABEL, CONSULT_SEASONS, INACTIVE_LABEL, NO_ACTIVE_TEXT, NO_SEASONS_TEXT, NO_SEASONS_TITLE, OTHER_HEADING,
  REFRESH_FAILED_TEXT, SEASONS_INTRO, SEASONS_LOADING, SEASONS_TITLE, seasonsReadMessage,
} from './messages';
import { SeasonActivation } from './SeasonActivation';

function SeasonRow({ season, action }: { season: Season; action: ReactNode }) {
  return (
    <li className="flex flex-wrap items-center gap-x-3 gap-y-2 px-4 py-4">
      <span className="min-w-16 text-lg font-bold">{season.anio}</span>
      <StatusBadge tone={season.activa ? 'positive' : 'neutral'} srLabel="Estado: ">{season.activa ? ACTIVE_LABEL : INACTIVE_LABEL}</StatusBadge>
      {action}
    </li>
  );
}

function Seasons({ seasons, disabled, refresh }: { seasons: Season[]; disabled: boolean; refresh: () => Promise<unknown> }) {
  const active = seasons.find((s) => s.activa);
  const others = seasons.filter((s) => !s.activa);
  return (
    <div className="max-w-3xl space-y-6">
      <header className="space-y-2">
        <h1 tabIndex={-1} className="text-3xl font-extrabold tracking-tight">{SEASONS_TITLE}</h1>
        <p className="text-sm text-muted">{SEASONS_INTRO}</p>
      </header>
      <CreateSeasonForm disabled={disabled} refresh={refresh} />
      {seasons.length === 0 ? (
        <section role="status" className="rounded-2xl border border-dashed border-line bg-white p-6">
          <h2 className="text-lg font-bold">{NO_SEASONS_TITLE}</h2>
          <p className="mt-2 text-sm leading-relaxed text-muted">{NO_SEASONS_TEXT}</p>
        </section>
      ) : (
        <SeasonActivation seasons={seasons} disabled={disabled} refresh={refresh}>
          {(action) => (
            <>
              <div className="space-y-2">
                <h2 className="text-base font-bold">{ACTIVE_HEADING}</h2>
                {active ? (
                  <ul aria-label={ACTIVE_HEADING} className="divide-y divide-line overflow-hidden rounded-2xl border border-line bg-white">
                    <SeasonRow season={active} action={null} />
                  </ul>
                ) : <p className="rounded-2xl border border-dashed border-line bg-white p-5 text-sm leading-relaxed text-muted">{NO_ACTIVE_TEXT}</p>}
              </div>
              {others.length > 0 ? (
                <div className="space-y-2">
                  <h2 className="text-base font-bold">{OTHER_HEADING}</h2>
                  <ul aria-label={OTHER_HEADING} className="divide-y divide-line overflow-hidden rounded-2xl border border-line bg-white">
                    {others.map((season) => <SeasonRow key={season.id} season={season} action={action(season)} />)}
                  </ul>
                </div>
              ) : null}
            </>
          )}
        </SeasonActivation>
      )}
    </div>
  );
}

function SeasonsScreen() {
  const seasons = useAllSeasons(true);
  // A failed REFRESH must not take the data off the screen: only an error with nothing to show is an error screen.
  if (seasons.isError && !seasons.data) {
    return (
      <div className="max-w-3xl space-y-4">
        <h1 tabIndex={-1} className="text-3xl font-extrabold tracking-tight">{SEASONS_TITLE}</h1>
        <Alert tone="error">{seasonsReadMessage(ApiError.from(seasons.error))}</Alert>
        <Button variant="outline" onClick={() => void seasons.refetch()}>Reintentar</Button>
      </div>
    );
  }
  if (!seasons.data) return <div className="max-w-3xl space-y-4"><LoadingBlock label={SEASONS_LOADING} /></div>;
  const refresh = () => seasons.refetch({ throwOnError: true });
  return (
    <>
      {seasons.isError ? (
        <div role="status" className="max-w-3xl space-y-2">
          <p>{REFRESH_FAILED_TEXT}</p>
          <Button variant="outline" onClick={() => void seasons.refetch()}>{CONSULT_SEASONS}</Button>
        </div>
      ) : null}
      <Seasons seasons={seasons.data} disabled={seasons.isError || seasons.isFetching} refresh={refresh} />
    </>
  );
}

/**
 * The seasons of the school, for ONE identity. Keyed by it, a session that now belongs to someone else starts from nothing (new queries,
 * no state of the previous administrator) instead of keeping what was already drawn.
 */
export function AdminTemporadasPage() {
  const session = useSession();
  const identity = session.status === 'authenticated' ? [session.user.id, session.user.rol, session.user.escuelaId, session.user.familiaId].join('|') : '';
  return <SeasonsScreen key={identity} />;
}
