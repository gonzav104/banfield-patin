import { useEffect, useId, useRef, useState, type ReactNode } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { Alert } from '@/components/ui/alert';
import { Button } from '@/components/ui/button';
import { ApiError } from '@/lib/api-error';
import type { LinkAction } from './api';
import { familyEligibilityKey, useChangeLink, useFamilyEligibility, useLinkWritePending } from './hooks';
import type { AthleteLinks, LinkRow } from './schemas';

type Phase = 'confirm' | 'stale' | 'uncertain' | 'rejected' | 'review';
type Intent = { row: LinkRow; action: LinkAction; version: string; phase: Phase; message?: string };
const title = (action: LinkAction) => action === 'revocar' ? 'Revocar vínculo' : 'Cambiar principal';
const explanation = (action: LinkAction) => action === 'revocar'
  ? 'Revocar retira el acceso de esta familia a los datos del deportista, conserva el historial y no promueve automáticamente otra familia a principal.'
  : 'Cambiar la familia principal no revoca las demás familias activas ni transfiere automáticamente al deportista.';

function Confirmation({ intent, busy, onCancel, children, returnFocus }: { intent: Intent; busy: boolean; onCancel: () => void; children: ReactNode; returnFocus: HTMLElement | null }) {
  const modal = useRef<HTMLDialogElement>(null);
  const heading = useId();
  const description = useId();
  useEffect(() => {
    const element = modal.current!;
    const trigger = returnFocus;
    element.showModal();
    element.querySelector<HTMLButtonElement>('[data-cancel]')?.focus();
    return () => {
      element.close();
      if (trigger instanceof HTMLElement && trigger.isConnected) trigger.focus();
      else document.querySelector<HTMLElement>('main h1')?.focus();
    };
  }, [returnFocus]);
  return (
    <dialog ref={modal} aria-labelledby={heading} aria-describedby={description} aria-modal="true"
      className="m-auto w-[calc(100%_-_2rem)] max-w-lg rounded-xl border border-line bg-white p-6 text-ink shadow-lg backdrop:bg-black/50"
      onKeyDown={(event) => {
        if (event.key !== 'Tab') return;
        const buttons = Array.from(event.currentTarget.querySelectorAll<HTMLButtonElement>('button:not(:disabled)'));
        const first = buttons[0];
        const last = buttons.at(-1);
        if (!first || !last) { event.preventDefault(); return; }
        if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last.focus(); }
        else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus(); }
      }}
      onCancel={(event) => { event.preventDefault(); if (!busy) onCancel(); }}>
      <h2 id={heading} className="text-xl font-bold">{title(intent.action)}</h2>
      <div id={description} className="my-4 space-y-2">
        <p className="break-words font-semibold">{intent.row.familiaNombre}</p>
        <p>{explanation(intent.action)}</p>
      </div>
      <div aria-busy={busy} className="space-y-4">{children}</div>
      <Button data-cancel autoFocus type="button" variant="outline" disabled={busy} onClick={onCancel} className="mt-4">Cancelar</Button>
    </dialog>
  );
}

function RowActions({ row, disabled, choose }: { row: LinkRow; disabled: boolean; choose: (row: LinkRow, action: LinkAction) => void }) {
  const eligible = useFamilyEligibility(row.familiaId, row.estado === 'ACTIVO');
  if (row.estado !== 'ACTIVO') return null;
  return (
    <div className="flex flex-wrap gap-2">
      <Button type="button" variant="outline" disabled={disabled} onClick={() => choose(row, 'revocar')}>Revocar vínculo</Button>
      {!row.esPrincipal ? (
        <Button type="button" variant="outline" disabled={disabled || eligible.data !== true || eligible.isError || eligible.isFetching}
          onClick={() => choose(row, 'principal')}>Cambiar principal</Button>
      ) : null}
      {!row.esPrincipal && eligible.data === false ? <p className="text-sm text-muted">La familia está inactiva: no puede ser principal.</p> : null}
      {!row.esPrincipal && eligible.isError ? <p role="status" className="text-sm text-muted">No pudimos verificar si la familia está habilitada.</p> : null}
    </div>
  );
}

/** One operation for the whole athlete screen. The revision belongs to the confirmation, not to the live query. */
export function LinkManagement({ snapshot, disabled, refresh, children }: {
  snapshot: AthleteLinks;
  disabled: boolean;
  refresh: () => Promise<unknown>;
  children: (actions: (row: LinkRow) => ReactNode) => ReactNode;
}) {
  const [intent, setIntent] = useState<Intent | null>(null);
  const [notice, setNotice] = useState('');
  const [needsVerification, setNeedsVerification] = useState(false);
  const [checking, setChecking] = useState(false);
  const sending = useRef(false);
  const returnFocus = useRef<HTMLElement | null>(null);
  const alive = useRef(true);
  const queryClient = useQueryClient();
  const mutation = useChangeLink(snapshot.deportistaId);
  const writePending = useLinkWritePending(snapshot.deportistaId);
  useEffect(() => { alive.current = true; return () => { alive.current = false; }; }, []);
  const busy = mutation.isPending || writePending || checking;

  function choose(row: LinkRow, action: LinkAction) {
    if (sending.current || intent || disabled || needsVerification || writePending) return;
    returnFocus.current = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    setNotice('');
    setIntent({ row, action, version: snapshot.versionVinculos, phase: 'confirm' });
  }

  function submit() {
    if (!intent || intent.phase !== 'confirm' || sending.current || writePending) return;
    sending.current = true;
    mutation.mutate({ athleteId: snapshot.deportistaId, familyId: intent.row.familiaId, linkId: intent.row.vinculoId,
      action: intent.action, version: intent.version, familyIds: snapshot.vinculos.map((row) => row.familiaId) }, {
      onSuccess: () => {
        sending.current = false;
        setNotice(intent.action === 'revocar' ? 'Vínculo revocado.' : 'Familia principal actualizada.');
        setIntent(null);
      },
      onError: (failure) => {
        sending.current = false;
        const error = ApiError.from(failure);
        let phase: Phase = 'rejected';
        let message = 'No se pudo autorizar la operación. Consultá el estado vigente antes de volver a confirmar.';
        if (error.code === 'EDICION_DESACTUALIZADA') {
          phase = 'stale';
          message = 'Los vínculos cambiaron. Consultá los vínculos vigentes y revisá una nueva confirmación.';
        } else if (error.code === 'CONFLICTO_CONCURRENCIA') {
          message = 'Otra operación está en curso. No se confirmó ningún cambio de esta petición.';
        } else if (error.code === 'VINCULO_NO_ACTIVO') {
          message = 'El vínculo ya no está activo. Consultá los vínculos vigentes.';
        } else if (error.code === 'FAMILIA_INACTIVA') {
          message = 'La familia está inactiva y no puede ser principal.';
        } else if (['network', 'unavailable', 'server', 'unknown'].includes(error.kind)) {
          phase = 'uncertain';
          message = 'No podemos confirmar el resultado de la operación. No se reintentará automáticamente; consultá los vínculos vigentes.';
        }
        setNeedsVerification(true);
        setNotice(message);
        setIntent({ ...intent, phase, message });
      },
    });
  }

  async function consult() {
    if (sending.current) return;
    sending.current = true;
    setChecking(true);
    try {
      await refresh();
      if (!alive.current) return;
      await queryClient.invalidateQueries({ queryKey: ['admin', 'families'] });
      if (!alive.current) return;
      setNeedsVerification(false);
      const message = 'Estado vigente consultado. La consulta no confirma el resultado de la petición anterior.';
      setNotice(message);
      if (intent) setIntent({ ...intent, phase: 'review', message });
    } catch {
      if (alive.current) {
        const message = 'No pudimos consultar los vínculos vigentes. No se reintentó la operación. Podés volver a consultar.';
        if (intent) setIntent({ ...intent, message });
        else setNotice(message);
      }
    } finally {
      if (alive.current) { sending.current = false; setChecking(false); }
    }
  }

  const current = intent ? snapshot.vinculos.find((row) => row.vinculoId === intent.row.vinculoId) : undefined;
  const canReconfirm = current?.estado === 'ACTIVO' && (intent?.action === 'revocar' ||
    (!current.esPrincipal && queryClient.getQueryData(familyEligibilityKey(current.familiaId)) === true &&
      queryClient.getQueryState(familyEligibilityKey(current.familiaId))?.status === 'success' &&
      queryClient.getQueryState(familyEligibilityKey(current.familiaId))?.fetchStatus === 'idle'));
  return (
    <>
      {writePending && !intent ? <p role="status">Hay una operación de vínculos en curso. Esperá su respuesta antes de confirmar otra.</p> : null}
      {notice && !intent ? <p role="status" className="rounded-lg border border-line p-4">{notice}</p> : null}
      {needsVerification && !intent ? <Button type="button" disabled={busy} onClick={() => void consult()}>Consultar vínculos vigentes</Button> : null}
      {children((row) => <RowActions row={row} disabled={disabled || busy || needsVerification || intent !== null} choose={choose} />)}
      {intent ? <Confirmation intent={intent} busy={busy} returnFocus={returnFocus.current} onCancel={() => { if (!sending.current) setIntent(null); }}>
        {intent.message ? <Alert>{intent.message}</Alert> : null}
        {intent.phase === 'confirm' ? <Button type="button" disabled={busy} onClick={submit}>
          {intent.action === 'revocar' ? 'Confirmar revocación' : 'Confirmar cambio de principal'}
        </Button> : null}
        {intent.phase !== 'confirm' && intent.phase !== 'review' ? <Button type="button" disabled={busy} onClick={() => void consult()}>Consultar vínculos vigentes</Button> : null}
        {intent.phase === 'review' && canReconfirm ? <Button type="button" disabled={busy || disabled} onClick={() => {
          if (current && !sending.current) setIntent({ row: current, action: intent.action, version: snapshot.versionVinculos, phase: 'confirm' });
        }}>Revisar nueva confirmación</Button> : null}
        {busy ? <p role="status">Esperando respuesta…</p> : null}
      </Confirmation> : null}
    </>
  );
}
