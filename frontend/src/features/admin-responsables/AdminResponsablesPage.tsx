import { useEffect, useRef, useState, type ReactNode } from 'react';
import { useParams } from 'react-router-dom';
import { Button } from '@/components/ui/button';
import { AthleteNotFound } from '@/features/admin-deportistas/AthleteNotFound';
import { AthleteStatusBadge } from '@/features/admin-deportistas/AthleteStatusBadge';
import { isSafeAthleteId } from '@/features/admin-deportistas/api';
import { useAthleteHeader } from '@/features/admin-datos-deportivos/hooks';
import { Row, SportsLink } from '@/features/admin-datos-deportivos/SportsUi';
import { useSession } from '@/features/auth/session';
import { LoadingBlock } from '@/features/familia/FamilyStates';
import { Pagination } from '@/features/familia/Pagination';
import { StatusBadge } from '@/features/familia/StatusBadge';
import { LinksNotice, LinksReadError } from '@/features/admin-vinculos/LinksUi';
import { tipoLabel } from '@/features/responsables/tipo';
import { ApiError } from '@/lib/api-error';
import { RESPONSABLES_PAGE_SIZE } from './api';
import { AltaResponsable } from './AltaResponsable';
import { ResponsableRowActions } from './ResponsableRowActions';
import { useResponsables } from './hooks';
import {
  ADD_INACTIVE_NOTE,
  BACK_TO_ATHLETE,
  causeLabel,
  CURRENT_EMPTY,
  CURRENT_TITLE,
  HISTORY_EMPTY,
  HISTORY_HIDE,
  HISTORY_NOTE,
  HISTORY_SHOW,
  HISTORY_TITLE,
  REFRESH_FAILED,
  REFRESH_RETRY,
  responsablesErrorMessage,
  SCREEN_LOADING,
  SCREEN_NOTE,
  SCREEN_TITLE,
} from './messages';
import { athleteDetailPath } from './navigation';
import type { Responsable } from './schemas';

const dateFormat = new Intl.DateTimeFormat('es-AR', { dateStyle: 'medium', timeZone: 'America/Argentina/Buenos_Aires' });
const day = (iso: string) => dateFormat.format(new Date(iso));
const absent = (error: ApiError) => error.status === 404 || error.status === 400;
const totalPages = (total: number) => Math.max(1, Math.ceil(total / RESPONSABLES_PAGE_SIZE));

/** One responsibility: name and declared type, and when it began. In the history a closed one also says why it ended; state is text, never only colour. */
function Item({ item, history, actions }: { item: Responsable; history: boolean; actions?: ReactNode }) {
  const closed = item.cerradoEn !== null;
  return (
    <li className="space-y-1 py-3 first:pt-0 last:pb-0">
      <div className="flex flex-wrap items-center gap-x-3 gap-y-1">
        <span className="min-w-0 break-words font-semibold">
          {item.nombreTutor} {item.apellidoTutor}
        </span>
        <span className="text-sm text-muted">{tipoLabel(item.tipo)}</span>
        {history ? <StatusBadge tone={closed ? 'neutral' : 'positive'}>{closed ? 'Cerrada' : 'Vigente'}</StatusBadge> : null}
      </div>
      <p className="text-sm text-muted">
        Desde {day(item.creadoEn)}
        {closed && item.cerradoEn && item.causaCierre ? ` · Hasta ${day(item.cerradoEn)} · ${causeLabel(item.causaCierre)}` : ''}
      </p>
      {actions}
    </li>
  );
}

function PagedList({ athleteId, historial, empty, onDone }: { athleteId: string; historial: boolean; empty: string; onDone?: (notice: string) => void }) {
  const [page, setPage] = useState(0);
  const query = useResponsables(athleteId, historial, page, true);
  if (query.isPending) return <LoadingBlock label={SCREEN_LOADING} />;
  if (query.isError && !query.data) {
    return <LinksReadError message={responsablesErrorMessage(ApiError.from(query.error))} onRetry={() => void query.refetch()} />;
  }
  const data = query.data;
  if (!data) return null;
  return (
    <div className="space-y-4">
      {query.isError ? (
        <div role="status" className="space-y-2">
          <p>{REFRESH_FAILED}</p>
          <Button variant="outline" onClick={() => void query.refetch()}>
            {REFRESH_RETRY}
          </Button>
        </div>
      ) : null}
      {data.contenido.length === 0 ? (
        <LinksNotice>{empty}</LinksNotice>
      ) : (
        <ul className="divide-y divide-line rounded-2xl border border-line bg-white p-5">
          {data.contenido.map((item) => (
            <Item key={item.id} item={item} history={historial}
              actions={!historial && onDone && item.cerradoEn === null ? <ResponsableRowActions athleteId={athleteId} row={item} disabled={query.isFetching} onDone={onDone} /> : undefined} />
          ))}
        </ul>
      )}
      {data.totalElementos > RESPONSABLES_PAGE_SIZE ? (
        <Pagination page={page} totalPages={totalPages(data.totalElementos)} total={data.totalElementos}
          onPrevious={() => setPage((p) => Math.max(0, p - 1))} onNext={() => setPage((p) => p + 1)} />
      ) : null}
    </div>
  );
}

function ResponsablesScreen() {
  const { id } = useParams();
  const valid = isSafeAthleteId(id);
  const athleteId = valid ? id : '';
  const header = useAthleteHeader(athleteId, valid);
  const [history, setHistory] = useState(false);
  const [notice, setNotice] = useState('');
  const heading = useRef<HTMLHeadingElement>(null);
  const ready = !!header.data;
  useEffect(() => {
    if (ready) heading.current?.focus(); // a new screen: keyboard and screen-reader users land on its title
  }, [ready]);

  if (!valid) return <AthleteNotFound />;
  if (header.isError && !header.data) {
    const error = ApiError.from(header.error);
    if (absent(error)) return <AthleteNotFound />;
    return (
      <div className="max-w-3xl space-y-4">
        <SportsLink to={athleteDetailPath(athleteId)}>{BACK_TO_ATHLETE}</SportsLink>
        <LinksReadError message={responsablesErrorMessage(error)} onRetry={() => void header.refetch()} />
      </div>
    );
  }
  if (!header.data) {
    return (
      <div className="max-w-3xl space-y-4">
        <SportsLink to={athleteDetailPath(athleteId)}>{BACK_TO_ATHLETE}</SportsLink>
        <LoadingBlock label={SCREEN_LOADING} />
      </div>
    );
  }
  const athlete = header.data;
  return (
    <div className="max-w-3xl space-y-6">
      <SportsLink to={athleteDetailPath(athlete.id)}>{BACK_TO_ATHLETE}</SportsLink>
      <header className="space-y-2">
        <h1 ref={heading} tabIndex={-1} className="text-3xl font-extrabold tracking-tight outline-none">
          {SCREEN_TITLE}
        </h1>
        <p className="min-w-0 break-words text-lg font-semibold">
          {athlete.nombre} {athlete.apellido}
        </p>
        <dl className="text-sm">
          <Row label="Estado en la escuela">
            <AthleteStatusBadge active={athlete.activo} />
          </Row>
        </dl>
        <p className="text-sm text-muted">{SCREEN_NOTE}</p>
      </header>
      <section aria-labelledby="responsables-vigentes" className="space-y-3">
        <h2 id="responsables-vigentes" className="text-lg font-bold">
          {CURRENT_TITLE}
        </h2>
        {notice ? <p role="status" className="rounded-lg border border-line p-4">{notice}</p> : null}
        <div className="space-y-2">
          <AltaResponsable athleteId={athlete.id} athleteActive={athlete.activo} disabled={false} onDone={setNotice} />
          {athlete.activo ? null : <p className="text-sm text-muted">{ADD_INACTIVE_NOTE}</p>}
        </div>
        <PagedList athleteId={athlete.id} historial={false} empty={CURRENT_EMPTY} onDone={setNotice} />
      </section>
      <section aria-labelledby="responsables-historial" className="space-y-3">
        <h2 id="responsables-historial" className="text-lg font-bold">
          {HISTORY_TITLE}
        </h2>
        <Button variant="outline" aria-expanded={history} aria-controls="responsables-historial-lista" onClick={() => setHistory((v) => !v)}>
          {history ? HISTORY_HIDE : HISTORY_SHOW}
        </Button>
        <div id="responsables-historial-lista">
          {history ? (
            <div className="space-y-3">
              <p className="text-sm text-muted">{HISTORY_NOTE}</p>
              <PagedList athleteId={athlete.id} historial empty={HISTORY_EMPTY} />
            </div>
          ) : null}
        </div>
      </section>
    </div>
  );
}

/**
 * The responsables of ONE athlete, for ONE identity. Keyed by both, a session that now belongs to someone else, or a different athlete,
 * starts from nothing (new queries, no state, no focus) instead of keeping what was already drawn. Read-only.
 */
export function AdminResponsablesPage() {
  const session = useSession();
  const { id } = useParams();
  const identity = session.status === 'authenticated' ? [session.user.id, session.user.rol, session.user.escuelaId, session.user.familiaId].join('|') : '';
  return <ResponsablesScreen key={`${identity}:${id ?? ''}`} />;
}
