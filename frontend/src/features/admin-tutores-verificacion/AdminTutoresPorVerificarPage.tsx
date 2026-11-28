import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useRef, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { Alert } from '@/components/ui/alert';
import { Button } from '@/components/ui/button';
import { useSession } from '@/features/auth/session';
import { LoadingBlock } from '@/features/familia/FamilyStates';
import { Pagination } from '@/features/familia/Pagination';
import { StatusBadge } from '@/features/familia/StatusBadge';
import { ApiError } from '@/lib/api-error';
import { BATCH_MAX, familyTutorPath, listPendingTutors, PAGE_SIZE, resolveTutor, verificationFilterSchema, verifyBatch, type BatchResult, type Decision, type PendingTutor, type VerificationFilter } from './api';
import { batchResultOk, batchResultText, classify, DNI_IMAGE_NOTE, documentState, failureText, LIST_ERROR_TEXT, OBSERVE_MAX, OBSERVE_WARNING } from './messages';

const DEFAULT_FILTER: VerificationFilter = 'DECLARADO';
const FILTER_OPTIONS: { value: VerificationFilter; label: string }[] = [
  { value: 'DECLARADO', label: 'Pendientes de verificar' },
  { value: 'OBSERVADO', label: 'Observados, esperan a la familia' },
];
const listKey = (estado: VerificationFilter, page: number) => ['admin', 'tutores-por-verificar', { estado, page }] as const;

function readFilter(value: string | null): VerificationFilter {
  const parsed = verificationFilterSchema.safeParse(value);
  return parsed.success ? parsed.data : DEFAULT_FILTER;
}

/** The address counts pages from 1; anything that is not a positive integer means the first page. */
function readPage(value: string | null): number {
  return value !== null && /^[1-9][0-9]{0,5}$/.test(value) ? Number(value) - 1 : 0;
}

type Notice = { tone: 'error' | 'success'; text: string };
type BatchOutcome = { name: string; result: string }[];

function Fact({ label, children, breakAll = false }: { label: string; children: string; breakAll?: boolean }) {
  return (
    <div className="flex min-w-0 flex-wrap gap-x-1">
      <dt className="font-semibold">{label}:</dt>
      <dd className={breakAll ? 'break-all' : 'break-words'}>{children}</dd>
    </div>
  );
}

function ObservePanel({ id, name, busy, onSend, onCancel }: { id: string; name: string; busy: boolean; onSend: (reason: string) => void; onCancel: () => void }) {
  const [reason, setReason] = useState('');
  const [error, setError] = useState<string | null>(null);
  const trimmed = reason.trim();

  function submit(event: React.FormEvent) {
    event.preventDefault();
    if (trimmed === '') return setError('Escribí qué tiene que corregir la familia.');
    if (trimmed.length > OBSERVE_MAX) return setError(`El motivo puede tener hasta ${OBSERVE_MAX} caracteres.`);
    setError(null);
    onSend(trimmed);
  }

  return (
    <form onSubmit={submit} noValidate aria-label={`Observar los datos de ${name}`} className="space-y-3 rounded-xl border border-amber-300 bg-amber-50 p-4">
      <label htmlFor={`reason-${id}`} className="block text-sm font-semibold">Qué tiene que corregir la familia</label>
      <p className="text-sm text-muted">{OBSERVE_WARNING}</p>
      <textarea
        id={`reason-${id}`}
        value={reason}
        maxLength={OBSERVE_MAX}
        rows={3}
        aria-invalid={error ? true : undefined}
        aria-describedby={error ? `reason-error-${id}` : undefined}
        onChange={(event) => {
          setReason(event.target.value);
          setError(null);
        }}
        className="block w-full rounded-lg border border-neutral-400 bg-white px-3 py-2 text-base"
      />
      {error ? <p id={`reason-error-${id}`} className="text-sm text-red-800">{error}</p> : null}
      <div className="flex flex-wrap gap-3">
        <Button type="submit" disabled={busy}>Enviar observación</Button>
        <Button type="button" variant="ghost" onClick={onCancel}>Cancelar</Button>
      </div>
    </form>
  );
}

function TutorRow({ tutor, selected, busy, onSelect, onVerify, onObserve }: { tutor: PendingTutor; selected: boolean; busy: boolean; onSelect: (on: boolean) => void; onVerify: () => void; onObserve: (reason: string) => void }) {
  const [observing, setObserving] = useState(false);
  const name = `${tutor.nombre} ${tutor.apellido}`;
  const declared = tutor.estado === 'DECLARADO';
  return (
    <li className="space-y-3 px-4 py-4">
      <div className="flex flex-wrap items-start gap-3">
        {declared ? (
          <input type="checkbox" className="mt-1 size-5" checked={selected} disabled={busy} aria-label={`Seleccionar a ${name}`} onChange={(event) => onSelect(event.target.checked)} />
        ) : null}
        <div className="min-w-0 flex-1 space-y-1">
          <p className="break-words font-semibold">
            {name}
            {tutor.parentesco ? <span className="font-normal text-muted"> · {tutor.parentesco}</span> : null}
          </p>
          <p className="text-sm text-muted">
            Familia:{' '}
            <Link to={familyTutorPath(tutor)} aria-label={`Ver la ficha de ${name}`} className="font-semibold text-brand-700 underline-offset-4 hover:underline">
              {tutor.familiaNombre ?? 'Ver la familia'}
            </Link>
          </p>
        </div>
        <StatusBadge tone={declared ? 'neutral' : 'warning'} srLabel="Estado: ">{declared ? 'Pendiente de verificar' : 'Observado'}</StatusBadge>
      </div>
      <dl className="grid gap-x-6 gap-y-1 text-sm sm:grid-cols-2">
        <Fact label="DNI">{tutor.dni ?? 'no informado'}</Fact>
        <Fact label="Teléfono">{tutor.telefono ?? 'no informado'}</Fact>
        <Fact label="Correo" breakAll>{tutor.email ?? 'no informado'}</Fact>
        <Fact label="Cargado por">{tutor.origen === 'ADMIN' ? 'el club' : 'la familia'}</Fact>
        <Fact label="DNI frente">{documentState(tutor.dniFrente)}</Fact>
        <Fact label="DNI dorso">{documentState(tutor.dniDorso)}</Fact>
      </dl>
      {tutor.coincidenciasEnOtrasFamilias > 0 ? (
        <Alert tone="warning">
          {tutor.coincidenciasEnOtrasFamilias === 1
            ? 'Hay 1 adulto de otra familia con el mismo DNI.'
            : `Hay ${tutor.coincidenciasEnOtrasFamilias} adultos de otra familia con el mismo DNI.`}
        </Alert>
      ) : null}
      {tutor.estado === 'OBSERVADO' && tutor.motivoObservacion ? <p className="text-sm text-muted">Motivo que ve la familia: {tutor.motivoObservacion}</p> : null}
      {observing ? (
        <ObservePanel id={tutor.tutorId} name={name} busy={busy} onSend={onObserve} onCancel={() => setObserving(false)} />
      ) : (
        <div className="flex flex-wrap gap-3">
          <Button type="button" disabled={busy} aria-label={`Verificar a ${name}`} onClick={onVerify}>Verificar</Button>
          <Button type="button" variant="outline" disabled={busy} aria-label={`Observar a ${name}`} onClick={() => setObserving(true)}>Observar</Button>
        </div>
      )}
    </li>
  );
}

function BatchResults({ outcome, onClose }: { outcome: BatchOutcome; onClose: () => void }) {
  const done = outcome.filter((item) => batchResultOk(item.result)).length;
  return (
    <section aria-label="Resultado de la verificación en lote" aria-live="polite" className="space-y-3 rounded-2xl border border-line bg-white p-4">
      <h2 className="text-base font-bold">Resultado de cada persona</h2>
      <p className="text-sm text-muted">{done} de {outcome.length} quedaron verificadas.</p>
      <ul className="divide-y divide-line text-sm">
        {outcome.map((item, index) => (
          <li key={index} className="flex flex-wrap justify-between gap-2 py-2">
            <span className="break-words font-semibold">{item.name}</span>
            <span className={batchResultOk(item.result) ? 'text-brand-900' : 'text-red-800'}>{batchResultText(item.result)}</span>
          </li>
        ))}
      </ul>
      <Button type="button" variant="outline" onClick={onClose}>Cerrar</Button>
    </section>
  );
}

function PendingList() {
  const [params, setParams] = useSearchParams();
  const estado = readFilter(params.get('estado'));
  const page = readPage(params.get('pagina'));
  const queryClient = useQueryClient();
  const query = useQuery({ queryKey: listKey(estado, page), queryFn: () => listPendingTutors({ estado, page }), placeholderData: keepPreviousData });
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [notice, setNotice] = useState<Notice | null>(null);
  const [outcome, setOutcome] = useState<BatchOutcome | null>(null);
  const sending = useRef(false);
  const data = query.data;

  const refresh = () => queryClient.invalidateQueries({ queryKey: ['admin', 'tutores-por-verificar'] });
  const decide = useMutation<void, ApiError, { tutorId: string; decision: Decision }>({ mutationFn: ({ tutorId, decision }) => resolveTutor(tutorId, decision) });
  const batch = useMutation<BatchResult[], ApiError, { tutorId: string; versionEsperada: string }[]>({ mutationFn: verifyBatch });
  const busy = decide.isPending || batch.isPending;

  function go(next: { estado?: VerificationFilter; page?: number }) {
    const nextFilter = next.estado ?? estado;
    const nextPage = next.page ?? 0;
    const search = new URLSearchParams();
    if (nextFilter !== DEFAULT_FILTER) search.set('estado', nextFilter);
    if (nextPage > 0) search.set('pagina', String(nextPage + 1));
    setSelected(new Set());
    setNotice(null);
    setParams(search);
  }

  function resolve(tutor: PendingTutor, decision: Decision, success: string) {
    if (sending.current) return;
    sending.current = true;
    setNotice(null);
    setOutcome(null);
    decide.mutate({ tutorId: tutor.tutorId, decision }, {
      onSuccess: () => {
        setNotice({ tone: 'success', text: success });
        setSelected((current) => new Set([...current].filter((id) => id !== tutor.tutorId)));
        void refresh();
      },
      onError: (failure) => {
        const error = ApiError.from(failure);
        setNotice({ tone: 'error', text: failureText(classify(error), error) });
        // Whatever the cause, the row on screen may be out of date: show what is stored now.
        void refresh();
      },
      onSettled: () => {
        sending.current = false;
      },
    });
  }

  const rows = data?.contenido ?? [];
  const selectable = rows.filter((row) => row.estado === 'DECLARADO');
  const chosen = selectable.filter((row) => selected.has(row.tutorId));

  function verifySelected() {
    if (sending.current || chosen.length === 0 || chosen.length > BATCH_MAX) return;
    sending.current = true;
    setNotice(null);
    const names = new Map(chosen.map((row) => [row.tutorId, `${row.nombre} ${row.apellido}`]));
    batch.mutate(chosen.map((row) => ({ tutorId: row.tutorId, versionEsperada: row.version })), {
      onSuccess: (results) => {
        setOutcome(results.map((item) => ({ name: names.get(item.tutorId) ?? 'Persona', result: item.resultado })));
        setSelected(new Set());
        void refresh();
      },
      onError: (failure) => {
        const error = ApiError.from(failure);
        setNotice({ tone: 'error', text: failureText(classify(error), error) });
        void refresh();
      },
      onSettled: () => {
        sending.current = false;
      },
    });
  }

  function toggle(tutorId: string, on: boolean) {
    setSelected((current) => {
      const next = new Set(current);
      if (on) next.add(tutorId);
      else next.delete(tutorId);
      return next;
    });
  }

  const totalPages = data ? Math.max(1, Math.ceil(data.totalElementos / PAGE_SIZE)) : 1;

  return (
    <div className="max-w-4xl space-y-6">
      <header>
        <p className="text-sm font-semibold text-brand-700">Administración</p>
        <h1 tabIndex={-1} className="mt-1 text-3xl font-extrabold tracking-tight">Tutores por verificar</h1>
        <p className="mt-2 text-muted">Datos que las familias declararon sobre otros adultos, del más antiguo al más reciente. Verificalos o pedí una corrección: no hace falta volver a escribirlos.</p>
        <p className="mt-2 text-sm text-muted">{DNI_IMAGE_NOTE}</p>
      </header>

      <section aria-label="Filtros" className="sm:max-w-xs">
        <label htmlFor="verification-filter" className="mb-1 block text-sm font-semibold">Mostrar</label>
        <select id="verification-filter" value={estado} onChange={(event) => go({ estado: readFilter(event.target.value) })} className="min-h-11 w-full rounded-lg border border-neutral-400 bg-white px-3 text-sm">
          {FILTER_OPTIONS.map((option) => <option key={option.value} value={option.value}>{option.label}</option>)}
        </select>
      </section>

      {notice ? <Alert tone={notice.tone} role={notice.tone === 'error' ? 'alert' : 'status'}>{notice.text}</Alert> : null}
      {outcome ? <BatchResults outcome={outcome} onClose={() => setOutcome(null)} /> : null}

      <section aria-label="Resultados" aria-busy={query.isFetching} className="space-y-3">
        {query.isError ? (
          <div className="space-y-3">
            <Alert tone="error">{LIST_ERROR_TEXT}</Alert>
            <Button variant="outline" onClick={() => void query.refetch()}>Reintentar</Button>
          </div>
        ) : query.isPending || !data ? (
          <LoadingBlock label="Cargando los tutores por verificar…" />
        ) : data.contenido.length === 0 && data.totalElementos > 0 ? (
          <div className="space-y-3 rounded-2xl border border-dashed border-line bg-white p-6 text-sm text-muted">
            <p>Esa página ya no existe.</p>
            <Button variant="outline" onClick={() => go({ page: 0 })}>Volver a la primera página</Button>
          </div>
        ) : data.contenido.length === 0 ? (
          <p role="status" className="rounded-2xl border border-dashed border-line bg-white p-6 text-sm text-muted">
            {estado === 'DECLARADO' ? 'No hay tutores pendientes de verificar.' : 'No hay tutores observados esperando a la familia.'}
          </p>
        ) : (
          <>
            <div className="flex flex-wrap items-center justify-between gap-3">
              <p role="status" className="text-sm text-muted">{data.totalElementos} {data.totalElementos === 1 ? 'tutor' : 'tutores'}</p>
              {selectable.length > 0 ? (
                <div className="flex flex-wrap items-center gap-3">
                  <Button type="button" variant="outline" disabled={busy} onClick={() => setSelected(new Set(chosen.length === selectable.length ? [] : selectable.map((row) => row.tutorId)))}>
                    {chosen.length === selectable.length ? 'Quitar la selección' : 'Seleccionar los de esta página'}
                  </Button>
                  <Button type="button" disabled={busy || chosen.length === 0} onClick={verifySelected}>
                    Verificar seleccionados{chosen.length > 0 ? ` (${chosen.length})` : ''}
                  </Button>
                </div>
              ) : null}
            </div>
            <ul aria-label="Tutores por verificar" className="divide-y divide-line overflow-hidden rounded-2xl border border-line bg-white">
              {data.contenido.map((tutor) => (
                <TutorRow
                  key={`${tutor.tutorId}:${tutor.version}`}
                  tutor={tutor}
                  selected={selected.has(tutor.tutorId)}
                  busy={busy}
                  onSelect={(on) => toggle(tutor.tutorId, on)}
                  onVerify={() => resolve(tutor, { decision: 'VERIFICAR', versionEsperada: tutor.version }, 'Listo: los datos quedaron verificados.')}
                  onObserve={(motivo) => resolve(tutor, { decision: 'OBSERVAR', motivo, versionEsperada: tutor.version }, 'Listo: la familia va a ver tu observación.')}
                />
              ))}
            </ul>
            {totalPages > 1 ? <Pagination page={page} totalPages={totalPages} total={data.totalElementos} onPrevious={() => go({ page: page - 1 })} onNext={() => go({ page: page + 1 })} /> : null}
          </>
        )}
      </section>
    </div>
  );
}

/** Keyed by identity: a session that now belongs to someone else starts from nothing, as every other administrative list does. */
export function AdminTutoresPorVerificarPage() {
  const session = useSession();
  const identity = session.status === 'authenticated' ? session.user.id : '';
  return <PendingList key={identity} />;
}
