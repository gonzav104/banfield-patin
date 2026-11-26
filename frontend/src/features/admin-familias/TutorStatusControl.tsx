import { useRef, useState } from 'react';
import { Alert } from '@/components/ui/alert';
import { Button } from '@/components/ui/button';
import { ConfirmDialog } from '@/features/admin-temporadas/ConfirmDialog';
import { ApiError } from '@/lib/api-error';
import { useChangeTutorStatus, useTutorDetail } from './hooks';
import type { TutorStatusAction } from './api';
import type { TutorDetail } from './schemas';

const TEXT = {
  desactivar: {
    button: 'Desactivar tutor',
    title: 'Desactivar tutor',
    confirm: 'Confirmar desactivación',
    done: 'El tutor quedó desactivado y sus responsabilidades vigentes se cerraron.',
    explain: 'Se cerrarán de forma irreversible todas las responsabilidades vigentes de este tutor. Reactivarlo después no las reabre: para volver a ser responsable hará falta una nueva confirmación. No cambia sus datos ni los documentos.',
  },
  activar: {
    button: 'Reactivar tutor',
    title: 'Reactivar tutor',
    confirm: 'Confirmar reactivación',
    done: 'El tutor quedó activo. Sus responsabilidades cerradas no se reabren.',
    explain: 'El tutor vuelve a estar activo y puede ser elegido como responsable. No reabre las responsabilidades que se cerraron al desactivarlo.',
  },
} as const;
const STALE = 'Los datos del tutor cambiaron mientras decidías. Mostramos el estado vigente: no se aplicó ningún cambio.';
const UNCERTAIN = 'No podemos confirmar el resultado. No se reintenta automáticamente; consultá el estado vigente del tutor.';
const REJECTED = 'No se pudo cambiar el estado del tutor. Consultá el estado vigente antes de volver a confirmar.';

type Issue = { kind: 'stale' | 'uncertain' | 'rejected'; message: string };
function classify(error: ApiError): Issue {
  if (error.code === 'EDICION_DESACTUALIZADA') return { kind: 'stale', message: STALE };
  if (error.status === 404 || error.status === 409 || error.status === 400 || error.kind === 'forbidden' || error.kind === 'csrf' || error.kind === 'validation') return { kind: 'rejected', message: REJECTED };
  return { kind: 'uncertain', message: UNCERTAIN };
}

/**
 * Activate / deactivate a tutor from its detail. The revision belongs to the confirmation: it is the one shown when the dialog opened,
 * whatever the live detail does in between. Nothing is retried automatically: a lost answer is verified by consulting the tutor. Deactivating
 * is stated as irreversible for the tutor's responsibilities.
 */
export function TutorStatusControl({ tutor, familyId, onNotice }: { tutor: TutorDetail; familyId: string; onNotice: (message: string) => void }) {
  const change = useChangeTutorStatus();
  const detail = useTutorDetail(tutor.id, false);
  const [open, setOpen] = useState<{ action: TutorStatusAction; version: string } | null>(null);
  const [issue, setIssue] = useState<Issue | null>(null);
  const returnFocus = useRef<HTMLElement | null>(null);
  const sending = useRef(false);
  const action: TutorStatusAction = tutor.activo ? 'desactivar' : 'activar';
  const text = TEXT[action];

  function openDialog() {
    if (sending.current || change.isPending) return;
    returnFocus.current = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    setIssue(null);
    setOpen({ action, version: tutor.version });
  }

  function confirm() {
    if (!open || sending.current || change.isPending) return;
    sending.current = true;
    change.mutate({ id: tutor.id, familyId, action: open.action, version: open.version }, {
      onSuccess: () => { sending.current = false; setOpen(null); onNotice(TEXT[open.action].done); },
      onError: (failure) => { sending.current = false; setIssue(classify(ApiError.from(failure))); },
    });
  }

  async function consult() {
    const result = await detail.refetch();
    if (result.isError) {
      setIssue({ kind: 'uncertain', message: 'No pudimos consultar el estado vigente del tutor. No se reintentó la operación. Podés volver a consultar.' });
      return;
    }
    setOpen(null);
    onNotice('Mostramos el estado vigente del tutor.');
  }

  return (
    <section aria-labelledby="tutor-status-title" className="space-y-3">
      <h2 id="tutor-status-title" className="text-lg font-bold">Estado</h2>
      <p className="text-sm text-muted">{tutor.activo ? 'El tutor está activo.' : 'El tutor está inactivo: no puede ser responsable de un deportista.'}</p>
      <Button type="button" variant="outline" disabled={change.isPending} onClick={openDialog}>{text.button}</Button>
      {open ? (
        <ConfirmDialog title={TEXT[open.action].title} busy={change.isPending} returnFocus={returnFocus.current}
          onCancel={() => { if (!sending.current) { setOpen(null); if (issue?.kind === 'stale') void detail.refetch(); } }}
          description={<><p className="break-words font-semibold">{tutor.nombre} {tutor.apellido}</p><p>{TEXT[open.action].explain}</p></>}>
          {issue ? <Alert tone="error">{issue.message}</Alert> : null}
          {!issue ? <Button type="button" disabled={change.isPending} onClick={confirm}>{TEXT[open.action].confirm}</Button> : null}
          {issue && issue.kind !== 'rejected' ? <Button type="button" disabled={change.isPending} onClick={() => void consult()}>Consultar estado vigente</Button> : null}
          {change.isPending ? <p role="status">Esperando respuesta…</p> : null}
        </ConfirmDialog>
      ) : null}
    </section>
  );
}
