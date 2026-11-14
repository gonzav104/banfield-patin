import { useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { Alert } from '@/components/ui/alert';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/field';
import { Pagination } from '@/features/familia/Pagination';
import { LoadingBlock } from '@/features/familia/FamilyStates';
import { useDebouncedValue } from '@/features/invitations/useDebouncedValue';
import { ApiError } from '@/lib/api-error';
import { FAMILY_PAGE_SIZE, SEARCH_MAX_LENGTH } from './api';
import { FamilyStatusBadge } from './FamilyStatusBadge';
import { useFamilyList } from './hooks';
import { listErrorMessage } from './messages';
import { familyStatusFilterSchema, type FamilyAdminSummary, type FamilyStatusFilter } from './schemas';

const SEARCH_DELAY_MS = 300;

const STATUS_OPTIONS: { value: FamilyStatusFilter; label: string }[] = [
  { value: 'TODOS', label: 'Todas' },
  { value: 'ACTIVOS', label: 'Activas' },
  { value: 'INACTIVOS', label: 'Inactivas' },
];

/** Status and page live in the address; the search term never does (it can be a family surname). */
function readStatus(value: string | null): FamilyStatusFilter {
  const parsed = familyStatusFilterSchema.safeParse(value);
  return parsed.success ? parsed.data : 'TODOS';
}

/** The address counts pages from 1; anything that is not a positive integer means the first page. */
function readPage(value: string | null): number {
  return value !== null && /^[1-9][0-9]{0,5}$/.test(value) ? Number(value) - 1 : 0;
}

function FamilyRow({ family }: { family: FamilyAdminSummary }) {
  return (
    <li className="grid gap-3 px-4 py-4 md:grid-cols-[minmax(0,1.5fr)_minmax(0,1fr)_auto] md:items-center">
      <p className="min-w-0 break-words font-semibold">
        <Link to={`/admin/familias/${encodeURIComponent(family.id)}`} className="text-ink underline-offset-4 hover:underline focus-visible:underline">
          {family.nombreReferencia}
        </Link>
      </p>
      <dl className="text-sm text-muted">
        <div className="flex gap-1">
          <dt>Tutores:</dt>
          <dd>{family.cantidadTutores}</dd>
        </div>
        <div className="flex gap-1">
          <dt>Deportistas activos:</dt>
          <dd>{family.cantidadDeportistasActivos}</dd>
        </div>
      </dl>
      <div className="md:justify-self-end">
        <FamilyStatusBadge active={family.activa} />
      </div>
    </li>
  );
}

export function AdminFamiliasPage() {
  const [params, setParams] = useSearchParams();
  const estado = readStatus(params.get('estado'));
  const page = readPage(params.get('pagina'));
  const [search, setSearch] = useState('');
  const busqueda = useDebouncedValue(search.trim(), SEARCH_DELAY_MS);

  const families = useFamilyList({ estado, busqueda, page });
  const data = families.data;
  const filtering = estado !== 'TODOS' || busqueda !== '';

  function go(next: { estado?: FamilyStatusFilter; page?: number }) {
    const nextStatus = next.estado ?? estado;
    const nextPage = next.page ?? 0;
    const query = new URLSearchParams();
    if (nextStatus !== 'TODOS') query.set('estado', nextStatus);
    if (nextPage > 0) query.set('pagina', String(nextPage + 1));
    setParams(query);
  }

  function clearFilters() {
    setSearch('');
    setParams(new URLSearchParams());
  }

  const totalPages = data ? Math.max(1, Math.ceil(data.totalElementos / FAMILY_PAGE_SIZE)) : 1;

  return (
    <div className="max-w-4xl space-y-6">
      <header className="flex flex-col gap-4 sm:flex-row sm:items-end sm:justify-between">
        <div>
          <p className="text-sm font-semibold text-brand-700">Administración</p>
          <h1 className="mt-1 text-3xl font-extrabold tracking-tight">Familias</h1>
          <p className="mt-2 text-muted">Consultá las familias de la escuela y cuántos tutores y deportistas activos tiene cada una.</p>
        </div>
        <Button asChild>
          <Link to="/admin/familias/nueva">Nueva familia</Link>
        </Button>
      </header>

      <section aria-label="Filtros" className="grid gap-3 sm:grid-cols-[minmax(0,1fr)_12rem] sm:items-end">
        <div>
          <label htmlFor="family-search" className="mb-1 block text-sm font-semibold">
            Buscar por nombre
          </label>
          <Input
            id="family-search"
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
          <label htmlFor="family-status-filter" className="mb-1 block text-sm font-semibold">
            Estado
          </label>
          <select
            id="family-status-filter"
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

      <section aria-label="Resultados" aria-busy={families.isFetching} className="space-y-3">
        {families.isError ? (
          <div className="space-y-3">
            <Alert tone="error">{listErrorMessage(ApiError.from(families.error))}</Alert>
            <Button variant="outline" onClick={() => void families.refetch()}>
              Reintentar
            </Button>
          </div>
        ) : families.isPending || !data ? (
          <LoadingBlock label="Cargando familias…" />
        ) : data.contenido.length === 0 && data.totalElementos > 0 ? (
          <div className="space-y-3 rounded-2xl border border-dashed border-line bg-white p-6 text-sm text-muted">
            <p>Esa página ya no existe.</p>
            <Button variant="outline" onClick={() => go({ page: 0 })}>
              Volver a la primera página
            </Button>
          </div>
        ) : data.contenido.length === 0 ? (
          <div className="space-y-3 rounded-2xl border border-dashed border-line bg-white p-6 text-sm text-muted">
            <p>{filtering ? 'No encontramos familias con esos criterios.' : 'Todavía no hay familias registradas.'}</p>
            {filtering ? (
              <Button variant="outline" onClick={clearFilters}>
                Limpiar filtros
              </Button>
            ) : null}
          </div>
        ) : (
          <>
            <p role="status" className="text-sm text-muted">
              {data.totalElementos} {data.totalElementos === 1 ? 'familia' : 'familias'}
            </p>
            <ul className="divide-y divide-line overflow-hidden rounded-2xl border border-line bg-white">
              {data.contenido.map((family) => (
                <FamilyRow key={family.id} family={family} />
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
