import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { Link, useSearchParams } from 'react-router-dom';
import { Alert } from '@/components/ui/alert';
import { Button } from '@/components/ui/button';
import { documentTypeLabel, documentsListErrorMessage } from '@/features/admin-deportistas/messages';
import { uploadDate } from '@/features/admin-deportistas/AthleteDocumentsView';
import { useSession } from '@/features/auth/session';
import { LoadingBlock } from '@/features/familia/FamilyStates';
import { Pagination } from '@/features/familia/Pagination';
import { StatusBadge } from '@/features/familia/StatusBadge';
import { ApiError } from '@/lib/api-error';
import { fichaPath, listPendingDocuments, PENDING_PAGE_SIZE, pendingOwnerFilterSchema, type PendingDocument, type PendingOwnerFilter } from './api';

const DEFAULT_FILTER: PendingOwnerFilter = 'TODOS';
const FILTER_OPTIONS: { value: PendingOwnerFilter; label: string }[] = [
  { value: 'TODOS', label: 'Todos' },
  { value: 'DEPORTISTA', label: 'Deportistas' },
  { value: 'TUTOR', label: 'Tutores' },
];
const OWNER_LABEL = { DEPORTISTA: 'Deportista', TUTOR: 'Tutor' } as const;

function readFilter(value: string | null): PendingOwnerFilter {
  const parsed = pendingOwnerFilterSchema.safeParse(value);
  return parsed.success ? parsed.data : DEFAULT_FILTER;
}

/** The address counts pages from 1; anything that is not a positive integer means the first page. */
function readPage(value: string | null): number {
  return value !== null && /^[1-9][0-9]{0,5}$/.test(value) ? Number(value) - 1 : 0;
}

function PendingRow({ document }: { document: PendingDocument }) {
  const path = fichaPath(document);
  const date = uploadDate(document.creadoEn);
  const type = documentTypeLabel(document.tipo);
  return (
    <li className="grid gap-2 px-4 py-4 md:grid-cols-[minmax(0,1.4fr)_minmax(0,1fr)_auto] md:items-center">
      <p className="min-w-0 break-words font-semibold">
        {path ? (
          <Link to={path} aria-label={`Revisar ${type} de ${document.titularNombre}`} className="text-ink underline-offset-4 hover:underline focus-visible:underline">
            {document.titularNombre}
          </Link>
        ) : document.titularNombre}
        <span className="ml-2 text-sm font-normal text-muted">{OWNER_LABEL[document.titularTipo]}</span>
      </p>
      <p className="min-w-0 break-words text-sm text-muted">
        {type} · Cargado el {date ?? 'fecha no informada'}
      </p>
      <div className="md:justify-self-end">
        {document.reemplazo ? <StatusBadge tone="neutral" srLabel="">Reemplazo de un documento observado</StatusBadge> : null}
      </div>
    </li>
  );
}

function PendingList() {
  const [params, setParams] = useSearchParams();
  const titular = readFilter(params.get('titular'));
  const page = readPage(params.get('pagina'));
  const pending = useQuery({
    queryKey: ['admin', 'pending-documents', { titular, page }],
    queryFn: () => listPendingDocuments({ titular, page }),
    placeholderData: keepPreviousData,
  });
  const data = pending.data;
  const filtering = titular !== DEFAULT_FILTER;

  function go(next: { titular?: PendingOwnerFilter; page?: number }) {
    const nextFilter = next.titular ?? titular;
    const nextPage = next.page ?? 0;
    const query = new URLSearchParams();
    if (nextFilter !== DEFAULT_FILTER) query.set('titular', nextFilter);
    if (nextPage > 0) query.set('pagina', String(nextPage + 1));
    setParams(query);
  }

  const totalPages = data ? Math.max(1, Math.ceil(data.totalElementos / PENDING_PAGE_SIZE)) : 1;

  return (
    <div className="max-w-4xl space-y-6">
      <header>
        <p className="text-sm font-semibold text-brand-700">Administración</p>
        <h1 tabIndex={-1} className="mt-1 text-3xl font-extrabold tracking-tight">Documentos pendientes</h1>
        <p className="mt-2 text-muted">
          Documentos cargados que esperan revisión, del más antiguo al más reciente. La revisión y la descarga se hacen en la ficha de cada persona.
        </p>
      </header>

      <section aria-label="Filtros" className="sm:max-w-xs">
        <label htmlFor="pending-owner-filter" className="mb-1 block text-sm font-semibold">Titular</label>
        <select
          id="pending-owner-filter"
          value={titular}
          onChange={(event) => go({ titular: readFilter(event.target.value), page: 0 })}
          className="min-h-11 w-full rounded-lg border border-neutral-400 bg-white px-3 text-sm"
        >
          {FILTER_OPTIONS.map((option) => <option key={option.value} value={option.value}>{option.label}</option>)}
        </select>
      </section>

      <section aria-label="Resultados" aria-busy={pending.isFetching} className="space-y-3">
        {pending.isError ? (
          <div className="space-y-3">
            <Alert tone="error">{documentsListErrorMessage(ApiError.from(pending.error))}</Alert>
            <Button variant="outline" onClick={() => void pending.refetch()}>Reintentar</Button>
          </div>
        ) : pending.isPending || !data ? (
          <LoadingBlock label="Cargando los documentos pendientes…" />
        ) : data.contenido.length === 0 && data.totalElementos > 0 ? (
          <div className="space-y-3 rounded-2xl border border-dashed border-line bg-white p-6 text-sm text-muted">
            <p>Esa página ya no existe.</p>
            <Button variant="outline" onClick={() => go({ page: 0 })}>Volver a la primera página</Button>
          </div>
        ) : data.contenido.length === 0 ? (
          <div role="status" className="space-y-3 rounded-2xl border border-dashed border-line bg-white p-6 text-sm text-muted">
            <p>{filtering ? 'No hay documentos pendientes para ese titular.' : 'No hay documentos pendientes de revisión.'}</p>
            {filtering ? <Button variant="outline" onClick={() => go({ titular: DEFAULT_FILTER })}>Ver todos</Button> : null}
          </div>
        ) : (
          <>
            <p role="status" className="text-sm text-muted">
              {data.totalElementos} {data.totalElementos === 1 ? 'documento pendiente' : 'documentos pendientes'}
            </p>
            <ul className="divide-y divide-line overflow-hidden rounded-2xl border border-line bg-white">
              {data.contenido.map((document) => <PendingRow key={document.id} document={document} />)}
            </ul>
            {totalPages > 1 ? (
              <Pagination page={page} totalPages={totalPages} total={data.totalElementos} onPrevious={() => go({ page: page - 1 })} onNext={() => go({ page: page + 1 })} />
            ) : null}
          </>
        )}
      </section>
    </div>
  );
}

/** Keyed by identity: a session that now belongs to someone else starts from nothing, as every other administrative list does. */
export function AdminDocumentosPendientesPage() {
  const session = useSession();
  const identity = session.status === 'authenticated' ? session.user.id : '';
  return <PendingList key={identity} />;
}
