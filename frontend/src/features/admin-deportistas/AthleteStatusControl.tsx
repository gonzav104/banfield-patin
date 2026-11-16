import { useRef, useState } from 'react';
import { Alert } from '@/components/ui/alert';
import { Button } from '@/components/ui/button';
import { ApiError } from '@/lib/api-error';
import type { AthleteStatusAction } from './api';
import { AthleteStatusBadge } from './AthleteStatusBadge';
import { useChangeAthleteStatus } from './hooks';
import {
  classifyEditFailure,
  detailErrorMessage,
  STALE_STATUS_TEXT,
  STATUS_VERIFY_MOVED,
  STATUS_VERIFY_UNCHANGED,
  statusErrorMessage,
  UNCERTAIN_STATUS_TEXT,
  type EditFailure,
} from './messages';
import type { AthleteDetail } from './schemas';

type Reloaded = { fresh: AthleteDetail; error?: undefined } | { error: ApiError; fresh?: undefined };

interface AthleteStatusControlProps {
  athlete: AthleteDetail;
  onNotice: (message: string) => void;
  /** Reloads the detail on request. */
  reload: () => Promise<Reloaded>;
}

interface Confirming {
  action: AthleteStatusAction;
  /** The revision seen when the confirmation was opened: it is the one sent, whatever happens in between. */
  version: string;
}

/**
 * Activate / deactivate from the athlete's own detail, with an explicit confirmation that states the consequences (verified in the
 * backend: nothing is deleted, family links and sports data do not change, an inactive athlete stops counting among the active
 * athletes of its family, and its DNI stays reserved). Only the action that fits the CURRENT state is offered, so an inactive
 * athlete can be reactivated from here. The revision sent is the one seen when the confirmation opened. After an answer that cannot
 * be confirmed nothing is resent until the administrator has verified the current state, and a current state that matches the one
 * asked for is never presented as proof that OUR request applied it.
 */
export function AthleteStatusControl({ athlete, onNotice, reload }: AthleteStatusControlProps) {
  const change = useChangeAthleteStatus();
  const [confirming, setConfirming] = useState<Confirming | null>(null);
  const [failure, setFailure] = useState<{ kind: EditFailure; error: ApiError; attempted: Confirming } | null>(null);
  const [checking, setChecking] = useState(false);
  const [reloadError, setReloadError] = useState<string | null>(null);
  const [verification, setVerification] = useState<'unchanged' | 'moved' | null>(null);
  /** Set synchronously on confirm: `isPending` only turns true after React Query notifies, so several clicks of ONE task would all pass it. */
  const sending = useRef(false);

  const action: AthleteStatusAction = athlete.activo ? 'desactivar' : 'activar';
  const blocked = failure?.kind === 'uncertain';

  function open(version: string) {
    change.reset();
    setFailure(null);
    setReloadError(null);
    setVerification(null);
    setConfirming({ action, version });
  }

  function confirm() {
    if (!confirming || sending.current || change.isPending) return; // a second click while one is in flight must never write twice
    const attempted = confirming;
    sending.current = true;
    setFailure(null);
    setReloadError(null);
    change.mutate(
      { id: athlete.id, action: attempted.action, version: attempted.version },
      {
        onSettled: () => {
          sending.current = false;
        },
        onSuccess: () => {
          setConfirming(null);
          onNotice(attempted.action === 'desactivar' ? 'El deportista quedó inactivo.' : 'El deportista quedó activo.');
        },
        onError: (error) => {
          const apiError = ApiError.from(error);
          setFailure({ kind: classifyEditFailure(apiError), error: apiError, attempted });
          setConfirming(null);
        },
      },
    );
  }

  async function checkCurrent() {
    if (!failure) return;
    const { kind, attempted } = failure;
    setChecking(true);
    setReloadError(null);
    const result = await reload();
    setChecking(false);
    if (result.error) {
      setReloadError(detailErrorMessage(result.error));
      return;
    }
    if (kind === 'stale') {
      setFailure(null);
      onNotice('Mostramos los datos vigentes.');
      return;
    }
    // Uncertain: the revision tells whether anything moved since the request was made. The current STATE is never read as proof.
    if (result.fresh.version === attempted.version) {
      setVerification('unchanged');
    } else {
      setFailure(null);
      setVerification('moved');
    }
  }

  const deactivating = confirming?.action === 'desactivar';
  const name = `${athlete.nombre} ${athlete.apellido}`;

  return (
    <section aria-labelledby="athlete-status-title" className="space-y-3 rounded-2xl border border-line bg-white p-5">
      <h2 id="athlete-status-title" className="text-base font-bold">
        Estado
      </h2>
      <div className="flex flex-wrap items-center gap-2 text-sm">
        <AthleteStatusBadge active={athlete.activo} />
        <span className="text-muted">{athlete.activo ? 'El deportista está activo.' : 'El deportista está inactivo: su DNI sigue reservado.'}</span>
      </div>

      {failure ? (
        <Alert tone="error">
          {failure.kind === 'stale' ? STALE_STATUS_TEXT : failure.kind === 'uncertain' ? UNCERTAIN_STATUS_TEXT : statusErrorMessage(failure.kind, failure.error)}
        </Alert>
      ) : null}
      {reloadError ? <Alert tone="error">{reloadError}</Alert> : null}
      {verification === 'moved' ? <Alert tone="warning">{STATUS_VERIFY_MOVED}</Alert> : null}
      {failure && (failure.kind === 'stale' || (failure.kind === 'uncertain' && verification === null)) ? (
        <Button variant="outline" disabled={checking} onClick={() => void checkCurrent()}>
          {checking ? 'Cargando…' : failure.kind === 'stale' ? 'Ver datos vigentes' : 'Verificar estado vigente'}
        </Button>
      ) : null}
      {failure?.kind === 'uncertain' && verification === 'unchanged' ? (
        <div role="group" aria-label="Verificación" className="space-y-3 rounded-lg border border-line bg-paper p-4 text-sm">
          <p>{STATUS_VERIFY_UNCHANGED}</p>
          <Button variant="outline" onClick={() => open(athlete.version)}>
            Volver a intentar
          </Button>
        </div>
      ) : null}

      {confirming ? (
        <div
          role="group"
          aria-label={deactivating ? 'Confirmar desactivación' : 'Confirmar reactivación'}
          className="space-y-3 rounded-lg border border-red-300 bg-red-50 p-4 text-sm"
        >
          <p className="break-words font-semibold">
            {deactivating ? '¿Desactivar' : '¿Reactivar'} a {name}?
          </p>
          {deactivating ? (
            <p>
              El deportista no se elimina y sus vínculos familiares y datos deportivos no cambian. Deja de contarse entre los deportistas activos de su familia y su DNI
              sigue reservado: no se podrá crear otro deportista con ese DNI. Cuando quieras, podés reactivarlo desde acá.
            </p>
          ) : (
            <p>
              El deportista vuelve a figurar como activo y a contarse entre los deportistas activos de su familia. Sus vínculos familiares y datos deportivos no cambian.
            </p>
          )}
          <div className="flex flex-col gap-2 sm:flex-row">
            <Button variant={deactivating ? 'dark' : 'primary'} disabled={change.isPending} aria-busy={change.isPending} onClick={confirm}>
              {change.isPending ? (deactivating ? 'Desactivando…' : 'Reactivando…') : deactivating ? 'Sí, desactivar' : 'Sí, reactivar'}
            </Button>
            <Button autoFocus variant="outline" disabled={change.isPending} onClick={() => setConfirming(null)}>
              Cancelar
            </Button>
          </div>
        </div>
      ) : blocked || (failure && failure.kind === 'stale') ? null : (
        <Button variant="outline" disabled={checking} onClick={() => open(athlete.version)}>
          {athlete.activo ? 'Desactivar deportista' : 'Reactivar deportista'}
        </Button>
      )}
    </section>
  );
}
