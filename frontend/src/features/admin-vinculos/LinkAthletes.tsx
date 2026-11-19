import { useEffect, useId, useRef, useState, type ReactNode } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { Button } from '@/components/ui/button';
import { ATHLETE_PAGE_SIZE, SEARCH_MAX_LENGTH } from '@/features/admin-deportistas/api';
import { useAthleteList } from '@/features/admin-deportistas/hooks';
import type { AthleteSummary } from '@/features/admin-deportistas/schemas';
import { Pagination } from '@/features/familia/Pagination';
import { ApiError } from '@/lib/api-error';
import { prepareFamilyLinks, type LinkBatchSnapshot } from './api';
import { linkIdentity, useFamilyAthletes, useFamilyLinkWrite } from './hooks';
import type { LinkRow } from './schemas';

type Intent = { snapshot: LinkBatchSnapshot; phase: 'confirm' | 'error' | 'review'; message?: string; reviewed?: LinkBatchSnapshot };
const explanation = 'La familia obtendrá acceso a la información de los deportistas seleccionados. Esta operación no transfiere deportistas ni revoca otras familias. La condición de familia principal la decide el backend; no se garantiza para el nuevo vínculo.';
const label = (row?: LinkRow | null) => !row ? 'Sin vínculo: se creará' : row.estado === 'ACTIVO' ? 'Activo: sin cambios' : row.estado === 'REVOCADO' ? 'Revocado: se reactivará' : 'Vínculo no disponible';
const actionLabel = (row: LinkRow | null) => !row ? 'Crear' : row.estado === 'REVOCADO' ? 'Reactivar' : 'Sin cambios';
function errorMessage(error: ApiError, write = false): string {
  switch (error.code) {
    case 'EDICION_DESACTUALIZADA': return 'Los vínculos cambiaron. Conservamos la selección. Consultá datos vigentes y revisá una nueva confirmación.';
    case 'CONFLICTO_CONCURRENCIA': return 'Otra operación está en curso. No se confirmó la vinculación de esta petición.';
    case 'FAMILIA_INACTIVA': return 'La familia está inactiva y no puede recibir vínculos.';
    case 'DEPORTISTA_INACTIVO': return 'Un deportista está inactivo y no puede vincularse. Revisá la selección.';
    case 'VINCULO_NO_ACTIVO': return 'El vínculo ya no está activo. Consultá los datos vigentes.';
    case 'VINCULO_PRINCIPAL_EN_CONFLICTO': return 'El vínculo principal cambió durante la operación. Consultá los datos vigentes.';
    case 'VINCULO_NO_DISPONIBLE': return 'Un vínculo tiene un estado no disponible para esta operación. Revisá la selección.';
  }
  if (write && ['network', 'unavailable', 'server', 'unknown'].includes(error.kind)) return 'No sabemos si la vinculación se aplicó. No vamos a reintentar automáticamente. Consultá los datos vigentes antes de decidir una nueva operación.';
  return write ? 'No se pudo autorizar la vinculación. Consultá datos vigentes antes de volver a confirmar.' : 'No pudimos consultar los datos necesarios. Conservamos la selección; intentá consultar nuevamente.';
}

function BatchDialog({ intent, busy, onClose, trigger, children }: { intent: Intent; busy: boolean; onClose: () => void; trigger: HTMLElement | null; children: ReactNode }) {
  const ref = useRef<HTMLDialogElement>(null);
  const title = useId(); const description = useId();
  useEffect(() => {
    const element = ref.current!; element.showModal(); element.querySelector<HTMLButtonElement>('[data-cancel]')?.focus();
    return () => { element.close(); if (trigger?.isConnected) trigger.focus(); };
  }, [trigger]);
  useEffect(() => {
    if (!busy) ref.current?.querySelector<HTMLButtonElement>('[data-primary]:not(:disabled), [data-cancel]:not(:disabled)')?.focus();
  }, [intent.phase, busy]);
  return <dialog ref={ref} tabIndex={-1} aria-modal="true" aria-labelledby={title} aria-describedby={description}
    className="m-auto max-h-[90vh] w-[calc(100%_-_2rem)] max-w-xl overflow-y-auto rounded-xl border border-line bg-white p-6 text-ink shadow-lg backdrop:bg-black/50"
    onCancel={(event) => { event.preventDefault(); if (!busy) onClose(); }}
    onKeyDown={(event) => {
      if (event.key !== 'Tab') return;
      const buttons = [...event.currentTarget.querySelectorAll<HTMLButtonElement>('button:not(:disabled)')];
      const first = buttons[0]; const last = buttons.at(-1);
      if (!first || !last) { event.preventDefault(); return; }
      if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last.focus(); }
      else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus(); }
    }}>
    <h2 id={title} className="text-xl font-bold">Confirmar vinculación de deportistas</h2>
    <p id={description} className="my-4">{explanation}</p>
    <ul className="my-3 list-inside list-disc">{(intent.phase === 'review' ? intent.reviewed! : intent.snapshot).entries.map((entry) => <li key={entry.athlete.id}>{entry.athlete.nombre} {entry.athlete.apellido} — {actionLabel(entry.relation)}</li>)}</ul>
    <div aria-busy={busy} className="space-y-3">{children}</div>
  </dialog>;
}

function Selector({ familyId, active, onClose }: { familyId: string; active: boolean; onClose: () => void }) {
  const client = useQueryClient(); const actor = linkIdentity(client);
  const alive = useRef(true); const sending = useRef(false); const trigger = useRef<HTMLElement | null>(null);
  useEffect(() => { alive.current = true; return () => { alive.current = false; }; }, []);
  const current = () => alive.current && !!actor && actor === linkIdentity(client);
  const [search, setSearch] = useState(''); const [term, setTerm] = useState(''); const [page, setPage] = useState(0);
  const [selected, setSelected] = useState<AthleteSummary[]>([]);
  const [intent, setIntent] = useState<Intent | null>(null); const [notice, setNotice] = useState('');
  const [checking, setChecking] = useState(false); const [needsVerification, setNeedsVerification] = useState(false);
  const list = useAthleteList({ estado: 'ACTIVOS', busqueda: term, page, size: ATHLETE_PAGE_SIZE });
  const links = useFamilyAthletes(familyId, true); const { mutation, pending, refresh } = useFamilyLinkWrite(familyId);
  const busy = checking || pending; const searchPending = search.trim() !== term;
  useEffect(() => { const timer = setTimeout(() => { setTerm(search.trim()); setPage(0); }, 300); return () => clearTimeout(timer); }, [search]);

  async function prepare() {
    if (sending.current || busy || !active || !selected.length || intent) return;
    trigger.current = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    sending.current = true; setChecking(true); setNotice('');
    try {
      const snapshot = await prepareFamilyLinks(familyId, selected, current);
      if (current()) { setIntent({ snapshot, phase: 'confirm' }); setNeedsVerification(false); }
    } catch (error) { if (current()) setNotice(errorMessage(ApiError.from(error))); }
    finally { sending.current = false; if (current()) setChecking(false); }
  }
  async function submit() {
    if (!intent || intent.phase !== 'confirm' || sending.current || busy || !active) return;
    sending.current = true;
    try {
      const result = await mutation.mutateAsync(intent.snapshot);
      if (!current()) return;
      const count = (outcome: string) => result.resultados.filter((r) => r.resultado === outcome).length;
      setNotice(`Vinculación confirmada: ${count('CREADO')} creados, ${count('REACTIVADO')} reactivados, ${count('SIN_CAMBIOS')} sin cambios.`);
      setIntent(null); setSelected([]); setNeedsVerification(false);
    } catch (failure) {
      if (!current()) return;
      const error = ApiError.from(failure);
      const affected = error.details.flatMap((d) => {
        const match = typeof d.campo === 'string' ? /^deportistas\[([0-9]{1,2})\]$/.exec(d.campo) : null;
        const entry = match ? intent.snapshot.entries[Number(match[1])] : null;
        return entry ? [`${entry.athlete.nombre} ${entry.athlete.apellido}`] : [];
      });
      setIntent({ snapshot: intent.snapshot, phase: 'error', message: errorMessage(error, true) + (affected.length ? ` Selección afectada: ${[...new Set(affected)].join(', ')}.` : '') });
      setNeedsVerification(true);
    } finally { sending.current = false; }
  }
  async function consult() {
    if (!intent || busy || sending.current) return;
    sending.current = true; setChecking(true);
    try {
      const reviewed = await prepareFamilyLinks(familyId, intent.snapshot.entries.map((e) => e.athlete), current);
      if (!current()) return;
      await refresh({ ...reviewed, familyIds: [...new Set([...intent.snapshot.familyIds, ...reviewed.familyIds])] });
      if (current()) setIntent({ ...intent, phase: 'review', reviewed, message: 'Datos vigentes consultados. Esto no confirma el resultado de la petición anterior. Revisá una nueva confirmación para autorizar otra operación.' });
    } catch (error) { if (current()) setIntent({ ...intent, phase: 'error', message: errorMessage(ApiError.from(error)) }); }
    finally { sending.current = false; if (current()) setChecking(false); }
  }
  const canSelect = !busy && !intent && active && !list.isFetching && !list.isPlaceholderData && !searchPending && !!links.data && !links.isError;
  return <div className="space-y-4 rounded-lg border border-line p-4" aria-label="Selección de deportistas">
    <h3 className="font-bold">Seleccionar deportistas existentes</h3>
    <p className="text-sm">Seleccioná hasta 50. Los vínculos activos se conservarán sin cambios.</p>
    <label className="block">Buscar deportistas<input className="block w-full rounded border p-2" value={search} maxLength={SEARCH_MAX_LENGTH} disabled={busy || !!intent} onChange={(e) => setSearch(e.target.value)} /></label>
    <p role="status">{selected.length} seleccionados de 50</p>
    {notice ? <p role={notice.startsWith('Vinculación confirmada:') ? 'status' : 'alert'}>{notice}</p> : null}
    {list.isError ? <div role="alert">No pudimos consultar los deportistas. <Button onClick={() => void list.refetch()} disabled={busy}>Reintentar listado</Button></div> : null}
    {links.isError ? <div role="alert">No pudimos consultar los vínculos. <Button onClick={() => void links.refetch()} disabled={busy}>Reintentar vínculos</Button></div> : null}
    {!list.data || !links.data ? <p role="status">Consultando deportistas y vínculos…</p> : null}
    {list.data ? <>
      {list.data.contenido.length === 0 ? <p>No hay deportistas para esta búsqueda.</p> : null}
      <ul className="space-y-2">{list.data.contenido.map((athlete) => {
        const row = links.data?.find((r) => r.deportistaId === athlete.id);
        const checked = selected.some((a) => a.id === athlete.id);
        const eligible = athlete.activo && (!row || row.estado === 'ACTIVO' || row.estado === 'REVOCADO');
        return <li key={athlete.id} className="rounded border border-line p-2"><label>
          <input type="checkbox" checked={checked} disabled={!canSelect || (!checked && (!eligible || selected.length >= 50))}
            onChange={() => { setNotice(''); setSelected((previous) => checked ? previous.filter((a) => a.id !== athlete.id) : [...previous, athlete]); }} />{' '}
          {athlete.nombre} {athlete.apellido}{athlete.dniEnmascarado ? ` · DNI ${athlete.dniEnmascarado}` : ''}
          <span className="block text-sm">{links.data ? label(row) : 'Consultando vínculo…'}{!athlete.activo ? ' · Deportista inactivo' : ''}</span>
        </label></li>;
      })}</ul>
      <fieldset disabled={busy || !!intent || list.isFetching || searchPending}><Pagination page={page} total={list.data.totalElementos} totalPages={Math.max(1, Math.ceil(list.data.totalElementos / ATHLETE_PAGE_SIZE))} onPrevious={() => setPage((p) => Math.max(0, p - 1))} onNext={() => setPage((p) => p + 1)} /></fieldset>
    </> : null}
    <div className="flex flex-wrap gap-3"><Button disabled={busy || !active || !selected.length || !!intent || !links.data} onClick={() => void prepare()}>{needsVerification ? 'Consultar y revisar selección' : 'Revisar selección'}</Button><Button variant="outline" disabled={busy || !!intent} onClick={onClose}>Cerrar selección</Button></div>
    {busy ? <p role="status">{checking ? 'Consultando datos vigentes…' : 'Vinculación en curso. No vuelvas a enviarla.'}</p> : null}
    {intent ? <BatchDialog intent={intent} busy={busy} trigger={trigger.current} onClose={() => setIntent(null)}>
      {intent.message ? <p role={intent.phase === 'error' ? 'alert' : 'status'}>{intent.message}</p> : null}
      <div className="flex flex-wrap gap-3"><Button data-cancel variant="outline" disabled={busy} onClick={() => setIntent(null)}>Cancelar</Button>
        {intent.phase === 'confirm' ? <Button disabled={busy || !active} onClick={() => void submit()}>Confirmar vinculación</Button> : null}
        {intent.phase === 'error' ? <Button data-primary disabled={busy} onClick={() => void consult()}>Consultar datos vigentes</Button> : null}
        {intent.phase === 'review' ? <Button data-primary disabled={busy || !active} onClick={() => { setIntent({ snapshot: intent.reviewed!, phase: 'confirm' }); setNeedsVerification(false); }}>Revisar nueva confirmación</Button> : null}
      </div>
    </BatchDialog> : null}
  </div>;
}

/** Mounted only inside the existing family detail. Opening is explicit: no extra list reads while closed. */
export function LinkAthletes({ familyId, active }: { familyId: string; active: boolean }) {
  const [open, setOpen] = useState(false); const { pending } = useFamilyLinkWrite(familyId);
  const triggerId = useId();
  return open ? <Selector familyId={familyId} active={active} onClose={() => { setOpen(false); queueMicrotask(() => document.getElementById(triggerId)?.focus()); }} />
    : <Button id={triggerId} variant="outline" disabled={!active || pending} onClick={() => setOpen(true)}>Vincular deportistas</Button>;
}
