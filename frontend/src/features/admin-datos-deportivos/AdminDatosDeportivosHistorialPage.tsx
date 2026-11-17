import { useParams } from 'react-router-dom';
import { AthleteNotFound } from '@/features/admin-deportistas/AthleteNotFound';
import { AthleteStatusBadge } from '@/features/admin-deportistas/AthleteStatusBadge';
import { useSession } from '@/features/auth/session';
import { LoadingBlock } from '@/features/familia/FamilyStates';
import { ApiError } from '@/lib/api-error';
import { isSafeId } from './api';
import { useAthleteHeader, useSeasonCount, useSportsHistory } from './hooks';
import {
  isAthleteAbsent,
  LOADING_TEXT,
  NO_ROWS_TEXT,
  NO_ROWS_TITLE,
  NO_SEASONS_TEXT,
  NO_SEASONS_TITLE,
  SCHOOL_STATE_NOTE,
  SECTION_TITLE,
} from './messages';
import { athleteDetailPath, sportsRowPath } from './navigation';
import type { AthleteHeader, SportsRow } from './schemas';
import { Notice, ReadError, Row, SportsLink, SportsStatusBadge, textOrNotInformed } from './SportsUi';

/** Newest year first, whatever order the server used. */
const newestFirst = (rows: SportsRow[]) => [...rows].sort((a, b) => b.anio - a.anio);

function SeasonItem({ athleteId, row }: { athleteId: string; row: SportsRow }) {
  return (
    <li className="rounded-2xl border border-line bg-white p-5">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <h2 className="text-lg font-bold">Temporada {row.anio}</h2>
        <SportsLink to={sportsRowPath(athleteId, row.temporadaId)}>Ver detalle de la temporada {row.anio}</SportsLink>
      </div>
      <dl className="mt-3 grid gap-3 text-sm sm:grid-cols-2">
        <Row label={`Estado deportivo de ${row.anio}`}>
          <SportsStatusBadge status={row.estadoDeportivo} />
        </Row>
        <Row label="Categoría">{textOrNotInformed(row.categoria)}</Row>
        <Row label="Rama">{textOrNotInformed(row.rama)}</Row>
        <Row label="Divisional">{textOrNotInformed(row.divisional)}</Row>
        <Row label="Número de corredor">{textOrNotInformed(row.numeroCorredor)}</Row>
      </dl>
    </li>
  );
}

/** An empty history: the school has no season at all, or it has some and this athlete has no data in any of them. */
function EmptyHistory() {
  const seasons = useSeasonCount(true);
  if (seasons.isError) return <ReadError error={ApiError.from(seasons.error)} onRetry={() => void seasons.refetch()} />;
  if (seasons.data === undefined) return <LoadingBlock label={LOADING_TEXT} />;
  return seasons.data === 0 ? <Notice title={NO_SEASONS_TITLE}>{NO_SEASONS_TEXT}</Notice> : <Notice title={NO_ROWS_TITLE}>{NO_ROWS_TEXT}</Notice>;
}

function History({ athlete, rows }: { athlete: AthleteHeader; rows: SportsRow[] }) {
  return (
    <div className="max-w-3xl space-y-6">
      <SportsLink to={athleteDetailPath(athlete.id)}>Volver al detalle del deportista</SportsLink>
      <header className="space-y-2">
        <h1 className="text-3xl font-extrabold tracking-tight">{SECTION_TITLE}</h1>
        <p className="min-w-0 break-words text-lg font-semibold">
          {athlete.nombre} {athlete.apellido}
        </p>
        <dl className="text-sm">
          <Row label="Estado en la escuela">
            <AthleteStatusBadge active={athlete.activo} />
          </Row>
        </dl>
        <p className="text-sm text-muted">{SCHOOL_STATE_NOTE}</p>
      </header>
      {rows.length === 0 ? (
        <EmptyHistory />
      ) : (
        <ul aria-label="Historial deportivo" className="space-y-4">
          {newestFirst(rows).map((row) => (
            <SeasonItem key={row.id} athleteId={athlete.id} row={row} />
          ))}
        </ul>
      )}
    </div>
  );
}

function HistoryScreen() {
  const { id } = useParams();
  const valid = isSafeId(id);
  const athleteId = valid ? id : '';
  const header = useAthleteHeader(athleteId, valid);
  const history = useSportsHistory(athleteId, valid);

  if (!valid) return <AthleteNotFound />;
  // A failed REFRESH must not take the data off the screen: only an error with nothing to show is an error screen.
  const failed = (header.isError && !header.data ? header : null) ?? (history.isError && !history.data ? history : null);
  if (failed) {
    const error = ApiError.from(failed.error);
    if (isAthleteAbsent(error)) return <AthleteNotFound />;
    return (
      <div className="max-w-3xl space-y-4">
        <SportsLink to={athleteDetailPath(athleteId)}>Volver al detalle del deportista</SportsLink>
        <ReadError
          error={error}
          onRetry={() => {
            if (header.isError) void header.refetch();
            if (history.isError) void history.refetch();
          }}
        />
      </div>
    );
  }
  if (!header.data || !history.data) {
    return (
      <div className="max-w-3xl space-y-4">
        <SportsLink to={athleteDetailPath(athleteId)}>Volver al detalle del deportista</SportsLink>
        <LoadingBlock label={LOADING_TEXT} />
      </div>
    );
  }
  return <History athlete={header.data} rows={history.data} />;
}

/**
 * The sports history of ONE athlete for ONE identity. Keyed by both, a session that now belongs to someone else, or a different
 * athlete, starts from nothing (new queries, no data of the previous one) instead of keeping what was already drawn.
 */
export function AdminDatosDeportivosHistorialPage() {
  const session = useSession();
  const { id } = useParams();
  const identity = session.status === 'authenticated' ? session.user.id : '';
  return <HistoryScreen key={`${identity}:${id ?? ''}`} />;
}

