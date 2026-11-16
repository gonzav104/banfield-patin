import { useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { Alert } from '@/components/ui/alert';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/field';
import { useSession } from '@/features/auth/session';
import { Pagination } from '@/features/familia/Pagination';
import { LoadingBlock } from '@/features/familia/FamilyStates';
import { useDebouncedValue } from '@/features/invitations/useDebouncedValue';
import { ApiError } from '@/lib/api-error';
import { ATHLETE_PAGE_SIZE, SEARCH_MAX_LENGTH } from './api';
import { AthleteStatusBadge } from './AthleteStatusBadge';
import { useAthleteList } from './hooks';
import { listErrorMessage } from './messages';
import { athleteStatusFilterSchema, type AthleteStatusFilter, type AthleteSummary } from './schemas';

const SEARCH_DELAY_MS = 300;

/** «Activos» is the default, as in the backend. */
const DEFAULT_STATUS: AthleteStatusFilter = 'ACTIVOS';

const STATUS_OPTIONS: { value: AthleteStatusFilter; label: string }[] = [
  { value: 'ACTIVOS', label: 'Activos' },
  { value: 'INACTIVOS', label: 'Inactivos' },
  { value: 'TODOS', label: 'Todos' },
];

/** Status and page live in the address; the search term never does (it can be a name or a DNI). */
function readStatus(value: string | null): AthleteStatusFilter {
  const parsed = athleteStatusFilterSchema.safeParse(value);
  return parsed.success ? parsed.data : DEFAULT_STATUS;
}

/** The address counts pages from 1; anything that is not a positive integer means the first page. */
function readPage(value: string | null): number {
  return value !== null && /^[1-9][0-9]{0,5}$/.test(value) ? Number(value) - 1 : 0;
}

function AthleteRow({ athlete, listSearch }: { athlete: AthleteSummary; listSearch: string }) {
  return (
    <li className="grid gap-3 px-4 py-4 md:grid-cols-[minmax(0,1.5fr)_minmax(0,1fr)_auto] md:items-center">
      <p className="min-w-0 break-words font-semibold">
        <Link to={`/admin/deportistas/${encodeURIComponent(athlete.id)}`} state={{ listSearch }} className="text-ink underline-offset-4 hover:underline focus-visible:underline">
          {athlete.apellido}, {athlete.nombre}
        </Link>
      </p>
      <dl className="text-sm text-muted">
        <div className="flex gap-1">
          <dt>DNI:</dt>
          <dd className="break-all">{athlete.dniEnmascarado || 'No informado'}</dd>
        </div>
      </dl>
      <div className="md:justify-self-end">
        <AthleteStatusBadge active={athlete.activo} />
      </div>
    </li>
  );
}

function AthleteList() {
  const [params, setParams] = useSearchParams();
  const estado = readStatus(params.get('estado'));
  const page = readPage(params.get('pagina'));
  const [search, setSearch] = useState('');
  const busqueda = useDebouncedValue(search.trim(), SEARCH_DELAY_MS);

  const athletes = useAthleteList({ estado, busqueda, page });
  const data = athletes.data;
  const filtering = estado !== DEFAULT_STATUS || busqueda !== '';

  function go(next: { estado?: AthleteStatusFilter; page?: number }) {
    const nextStatus = next.estado ?? estado;
    const nextPage = next.page ?? 0;
    const query = new URLSearchParams();
    if (nextStatus !== DEFAULT_STATUS) query.set('estado', nextStatus);
    if (nextPage > 0) query.set('pagina', String(nextPage + 1));
    setParams(query);
  }

  function clearFilters() {
    setSearch('');
    setParams(new URLSearchParams());
  }

  const totalPages = data ? Math.max(1, Math.ceil(data.totalElementos / ATHLETE_PAGE_SIZE)) : 1;

  return (
    <div className="max-w-4xl space-y-6">
      <header className="flex flex-col gap-4 sm:flex-row sm:items-end sm:justify-between">
        <div>
          <p className="text-sm font-semibold text-brand-700">Administración</p>
          <h1 className="mt-1 text-3xl font-extrabold tracking-tight">Deportistas</h1>
          <p className="mt-2 text-muted">Consultá los deportistas de la escuela. El DNI se muestra parcialmente: el completo está en el detalle.</p>
        </div>
      </header>

      <section aria-label="Filtros" className="grid gap-3 sm:grid-cols-[minmax(0,1fr)_12rem] sm:items-end">
        <div>
          <label htmlFor="athlete-search" className="mb-1 block text-sm font-semibold">
            Buscar por nombre, apellido o DNI
          </label>
          <Input
            id="athlete-search"
            type="search"
            value={search}
            maxLength={SEARCH_MAX_LENGTH}
            autoComplete="off"
            spellCheck={false}
            onChange={(event) => {
              setSearch(event.target.value);
              if (page > 0) go({ page: 0 });
            }}
          />
        </div>
        <div>
          <label htmlFor="athlete-status-filter" className="mb-1 block text-sm font-semibold">
            Estado
          </label>
          <select
            id="athlete-status-filter"
            value={estado}
            onChange={(event) => go({ estado: readStatus(event.target.value), page: 0 })}
            className="min-h-11 w-full rounded-lg border border-neutral-400 bg-white px-3 text-sm"
          >
            {STATUS_OPTIONS.map((option) => (
              <option key={option.value} value={option.value}>
                {option.label}
              </option>
            ))}
          </select>
        </div>
      </section>

      <section aria-label="Resultados" aria-busy={athletes.isFetching} className="space-y-3">
        {athletes.isError ? (
          <div className="space-y-3">
            <Alert tone="error">{listErrorMessage(ApiError.from(athletes.error))}</Alert>
            <Button variant="outline" onClick={() => void athletes.refetch()}>
              Reintentar
            </Button>
          </div>
        ) : athletes.isPending || !data ? (
          <LoadingBlock label="Cargando deportistas…" />
        ) : data.contenido.length === 0 && data.totalElementos > 0 ? (
          <div className="space-y-3 rounded-2xl border border-dashed border-line bg-white p-6 text-sm text-muted">
            <p>Esa página ya no existe.</p>
            <Button variant="outline" onClick={() => go({ page: 0 })}>
              Volver a la primera página
            </Button>
          </div>
        ) : data.contenido.length === 0 ? (
          <div className="space-y-3 rounded-2xl border border-dashed border-line bg-white p-6 text-sm text-muted">
            <p>{filtering ? 'No encontramos deportistas con esos criterios.' : 'Todavía no hay deportistas registrados.'}</p>
            {filtering ? (
              <Button variant="outline" onClick={clearFilters}>
                Limpiar filtros
              </Button>
            ) : null}
          </div>
        ) : (
          <>
            <p role="status" className="text-sm text-muted">
              {data.totalElementos} {data.totalElementos === 1 ? 'deportista' : 'deportistas'}
            </p>
            <ul className="divide-y divide-line overflow-hidden rounded-2xl border border-line bg-white">
              {data.contenido.map((athlete) => (
                <AthleteRow key={athlete.id} athlete={athlete} listSearch={params.toString()} />
              ))}
            </ul>
            {totalPages > 1 ? (
              <Pagination
                page={page}
                totalPages={totalPages}
                total={data.totalElementos}
                onPrevious={() => go({ page: page - 1 })}
                onNext={() => go({ page: page + 1 })}
              />
            ) : null}
          </>
        )}
      </section>
    </div>
  );
}

/**
 * The list holds, in this component's memory, a search term that can be a name or a DNI, and what the query returned. Keyed by
 * identity, a session that now belongs to someone else starts from nothing (no term, no rows, a new query) instead of keeping
 * what the previous administrator typed; the session code also removes the previous identity's queries, which aborts any
 * response still in flight, so a late answer cannot populate the new screen.
 */
export function AdminDeportistasPage() {
  const session = useSession();
  const identity = session.status === 'authenticated' ? session.user.id : '';
  return <AthleteList key={identity} />;
}
