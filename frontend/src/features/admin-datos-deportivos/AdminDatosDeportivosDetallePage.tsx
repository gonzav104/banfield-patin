import { useLocation, useParams } from 'react-router-dom';
import { Alert } from '@/components/ui/alert';
import { Button } from '@/components/ui/button';
import { AthleteNotFound } from '@/features/admin-deportistas/AthleteNotFound';
import { useSession } from '@/features/auth/session';
import { LoadingBlock } from '@/features/familia/FamilyStates';
import { ApiError } from '@/lib/api-error';
import { isSafeId } from './api';
import { useSportsRow } from './hooks';
import { classifyRowAbsence, EDIT_LINK, EDITED_NOTICE, LOADED_NOTICE, LOADING_TEXT, NO_ROW_TEXT, NO_ROW_TITLE, SECTION_TITLE, SEASON_NOT_FOUND_TEXT, SEASON_NOT_FOUND_TITLE } from './messages';
import { sportsHistoryPath, sportsRowPath } from './navigation';
import type { SportsRow } from './schemas';
import { Notice, ReadError, Row, SportsLink, SportsStatusBadge, textOrNotInformed } from './SportsUi';

function BackToHistory({ athleteId }: { athleteId: string }) {
  return <SportsLink to={sportsHistoryPath(athleteId)}>Volver al historial deportivo</SportsLink>;
}

/** A season or a row that cannot be shown: one calm screen, a way back to the history, and nothing created from here. */
function Absent({ athleteId, title, children }: { athleteId: string; title: string; children: string }) {
  return (
    <div className="max-w-2xl space-y-4">
      <BackToHistory athleteId={athleteId} />
      <Notice title={title}>{children}</Notice>
    </div>
  );
}

/** The revision (`version`) is part of the contract and is deliberately not read here: it is not a business datum. */
function RowView({ athleteId, row, notice }: { athleteId: string; row: SportsRow; notice: string | null }) {
  return (
    <div className="max-w-3xl space-y-6">
      <BackToHistory athleteId={athleteId} />
      {notice ? <Alert tone="success">{notice}</Alert> : null}
      <header className="space-y-2">
        <p className="text-sm font-semibold text-brand-700">{SECTION_TITLE}</p>
        <h1 className="text-3xl font-extrabold tracking-tight">Temporada {row.anio}</h1>
        <Button asChild variant="outline">
          <SportsLink to={`${sportsRowPath(athleteId, row.temporadaId)}/editar`} className="">
            {EDIT_LINK}
          </SportsLink>
        </Button>
      </header>
      <section aria-label={`Datos de la temporada ${row.anio}`} className="rounded-2xl border border-line bg-white p-5">
        <dl className="grid gap-3 text-sm sm:grid-cols-2">
          <Row label="Categoría">{textOrNotInformed(row.categoria)}</Row>
          <Row label="Rama">{textOrNotInformed(row.rama)}</Row>
          <Row label="Divisional">{textOrNotInformed(row.divisional)}</Row>
          <Row label="Número de corredor">{textOrNotInformed(row.numeroCorredor)}</Row>
          <Row label={`Estado deportivo de ${row.anio}`}>
            <SportsStatusBadge status={row.estadoDeportivo} />
          </Row>
        </dl>
      </section>
    </div>
  );
}

function RowScreen() {
  const { id, temporadaId } = useParams();
  const location = useLocation();
  const validAthlete = isSafeId(id);
  const validSeason = isSafeId(temporadaId);
  const enabled = validAthlete && validSeason;
  const row = useSportsRow(enabled ? id : '', enabled ? temporadaId : '', enabled);

  if (!validAthlete) return <AthleteNotFound />;
  if (!validSeason) return <Absent athleteId={id} title={SEASON_NOT_FOUND_TITLE}>{SEASON_NOT_FOUND_TEXT}</Absent>;
  // A failed REFRESH must not take the data off the screen: only an error with nothing to show is an error screen.
  if (row.isError && !row.data) {
    const error = ApiError.from(row.error);
    switch (classifyRowAbsence(error)) {
      case 'athlete':
        return <AthleteNotFound />;
      case 'row':
        return <Absent athleteId={id} title={NO_ROW_TITLE}>{NO_ROW_TEXT}</Absent>;
      case 'season':
        return <Absent athleteId={id} title={SEASON_NOT_FOUND_TITLE}>{SEASON_NOT_FOUND_TEXT}</Absent>;
      default:
        return (
          <div className="max-w-3xl space-y-4">
            <BackToHistory athleteId={id} />
            <ReadError error={error} onRetry={() => void row.refetch()} />
          </div>
        );
    }
  }
  if (!row.data) {
    return (
      <div className="max-w-3xl space-y-4">
        <BackToHistory athleteId={id} />
        <LoadingBlock label={LOADING_TEXT} />
      </div>
    );
  }
  // The notice after a load travels in the navigation state as a FLAG, never as data; a detail opened directly never shows it.
  const state = location.state as { sportsRowCreated?: unknown; sportsRowUpdated?: unknown } | null;
  const notice = state?.sportsRowCreated === true ? LOADED_NOTICE : state?.sportsRowUpdated === true ? EDITED_NOTICE : null;
  return <RowView athleteId={id} row={row.data} notice={notice} />;
}

/**
 * ONE season of ONE athlete for ONE identity. Keyed by the three, a session that now belongs to someone else, a different athlete or
 * a different season starts from nothing (a new query, no data of the previous one) instead of keeping what was already drawn.
 */
export function AdminDatosDeportivosDetallePage() {
  const session = useSession();
  const { id, temporadaId } = useParams();
  const identity = session.status === 'authenticated' ? session.user.id : '';
  return <RowScreen key={`${identity}:${id ?? ''}:${temporadaId ?? ''}`} />;
}
