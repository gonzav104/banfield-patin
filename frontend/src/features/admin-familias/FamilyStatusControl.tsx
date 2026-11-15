import { useEffect, useState } from 'react';
import { Alert } from '@/components/ui/alert';
import { Button } from '@/components/ui/button';
import { ApiError } from '@/lib/api-error';
import type { FamilyStatusAction } from './api';
import { useChangeFamilyStatus } from './hooks';
import { classifyWriteFailure, detailErrorMessage, STALE_STATUS_TEXT, UNCERTAIN_STATUS_TEXT, writeErrorMessage, type WriteFailure } from './messages';
import type { FamilyDetail } from './schemas';

interface FamilyStatusControlProps {
  family: FamilyDetail;
  /** True while another write (the name editor) is open: only one operation at a time. */
  disabled: boolean;
  onNotice: (message: string) => void;
  /** Reloads the detail on request. Resolves to the error, or null when the current data arrived. */
  reload: () => Promise<ApiError | null>;
}

interface Confirming {
  action: FamilyStatusAction;
  /** The revision seen when the confirmation was opened: it is the one sent, whatever happens in between. */
  version: string;
}

/**
 * Activate / deactivate with an explicit confirmation that states the consequences (all verified in the backend:
 * a family that is not active cannot sign in and its open sessions stop being valid, tutors and links cannot be created
 * or edited, and nothing cascades onto athletes, links or tutors).
 */
export function FamilyStatusControl({ family, disabled, onNotice, reload }: FamilyStatusControlProps) {
  const change = useChangeFamilyStatus();
  const [confirming, setConfirming] = useState<Confirming | null>(null);
  const [failure, setFailure] = useState<{ kind: WriteFailure; error: ApiError } | null>(null);
  const [checking, setChecking] = useState(false);
  const [reloadError, setReloadError] = useState<string | null>(null);

  useEffect(() => {
    if (disabled) setConfirming(null); // a name edit started: only one operation at a time
  }, [disabled]);

  const action: FamilyStatusAction = family.activa ? 'desactivar' : 'activar';

  function confirm() {
    if (!confirming || change.isPending) return; // a second click while one is in flight must never write twice
    setFailure(null);
    setReloadError(null);
    change.mutate(
      { id: family.id, action: confirming.action, version: confirming.version },
      {
        onSuccess: () => {
          setConfirming(null);
          onNotice(confirming.action === 'desactivar' ? 'La familia quedó desactivada.' : 'La familia quedó activa.');
        },
        onError: (error) => {
          const apiError = ApiError.from(error);
          setFailure({ kind: classifyWriteFailure(apiError), error: apiError });
          setConfirming(null);
        },
      },
    );
  }

  async function checkCurrentData() {
    setChecking(true);
    setReloadError(null);
    const error = await reload();
    setChecking(false);
    if (error) {
      setReloadError(detailErrorMessage(error));
      return;
    }
    setFailure(null);
    onNotice('Mostramos los datos vigentes.');
  }

  const deactivating = confirming?.action === 'desactivar';

  return (
    <section aria-labelledby="status-title" className="space-y-3">
      <h2 id="status-title" className="text-lg font-bold">
        Estado
      </h2>
      <p className="text-sm text-muted">{family.activa ? 'La familia está activa.' : 'La familia está inactiva: no puede ingresar al portal.'}</p>

      {failure ? (
        <Alert tone="error">
          {failure.kind === 'stale'
            ? STALE_STATUS_TEXT
            : failure.kind === 'uncertain'
              ? UNCERTAIN_STATUS_TEXT
              : writeErrorMessage(failure.kind, failure.error, 'status')}
        </Alert>
      ) : null}
      {reloadError ? <Alert tone="error">{reloadError}</Alert> : null}
      {failure && (failure.kind === 'stale' || failure.kind === 'uncertain') ? (
        <Button variant="outline" disabled={checking} onClick={() => void checkCurrentData()}>
          {checking ? 'Cargando…' : failure.kind === 'stale' ? 'Ver datos vigentes' : 'Verificar datos vigentes'}
        </Button>
      ) : null}

      {confirming ? (
        <div
          role="group"
          aria-label={deactivating ? 'Confirmar desactivación' : 'Confirmar activación'}
          className="space-y-3 rounded-lg border border-red-300 bg-red-50 p-4 text-sm"
        >
          <p className="font-semibold">
            {deactivating ? '¿Desactivar' : '¿Activar'} a {family.nombreReferencia}?
          </p>
          {deactivating ? (
            <p>
              Las personas de esta familia dejarán de poder ingresar al portal y sus sesiones abiertas dejarán de ser válidas. No se elimina su
              historial y no se desactiva a sus deportistas, vínculos ni tutores. Mientras esté inactiva no se podrán crear ni editar tutores ni
              vínculos. Cuando quieras, podés reactivarla.
            </p>
          ) : (
            <p>
              Las personas de esta familia podrán volver a ingresar al portal. Sus deportistas, vínculos y tutores no cambian. También se
              podrán volver a crear y editar tutores y vínculos.
            </p>
          )}
          <div className="flex flex-col gap-2 sm:flex-row">
            <Button variant={deactivating ? 'dark' : 'primary'} disabled={change.isPending} aria-busy={change.isPending} onClick={confirm}>
              {change.isPending ? (deactivating ? 'Desactivando…' : 'Activando…') : deactivating ? 'Sí, desactivar' : 'Sí, activar'}
            </Button>
            <Button autoFocus variant="outline" disabled={change.isPending} onClick={() => setConfirming(null)}>
              Cancelar
            </Button>
          </div>
        </div>
      ) : (
        <Button
          variant="outline"
          disabled={disabled || checking}
          onClick={() => {
            change.reset();
            setFailure(null);
            setReloadError(null);
            setConfirming({ action, version: family.version });
          }}
        >
          {family.activa ? 'Desactivar familia' : 'Activar familia'}
        </Button>
      )}
    </section>
  );
}
