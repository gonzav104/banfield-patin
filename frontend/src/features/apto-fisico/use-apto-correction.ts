import { useQueryClient } from '@tanstack/react-query';
import { useLayoutEffect, useRef, useState } from 'react';
import type { AuthUser } from '@/features/auth/api';
import { sessionKey } from '@/features/auth/session';
import { ApiError } from '@/lib/api-error';
import { correctApto } from './api';
import type { AptoData } from './form';
import { aptoKeys } from './hooks';

export type CorrectionFeedback = { tone: 'success' | 'error'; message: string };

const CONFLICT_TEXT: Record<string, string> = {
  EDICION_DESACTUALIZADA: 'El apto cambió mientras lo editabas. Actualizamos la información: revisá los datos y volvé a corregir.',
  APTO_NO_CORREGIBLE: 'Un apto observado no se corrige: la familia lo reemplaza. Actualizamos la información.',
  DOCUMENTO_NO_VIGENTE: 'Este apto ya fue reemplazado. Actualizamos la información.',
  CONFLICTO_CONCURRENCIA: 'Otra persona está modificando este apto. Esperá unos segundos y volvé a intentar.',
  VERSION_DOCUMENTO_AGOTADA: 'No se puede modificar este apto.',
};

/** Fixed texts: the server message and the values typed are never shown. A 400 keeps the dialog open to fix the data. */
export function correctionFailure(error: unknown): { message: string; keepOpen: boolean } {
  const failure = ApiError.from(error);
  if (failure.status === 400 && failure.code === 'VALIDACION') return { message: 'Revisá los datos: alguno no es válido.', keepOpen: true };
  if (failure.status === 409 && failure.code && Object.hasOwn(CONFLICT_TEXT, failure.code)) return { message: CONFLICT_TEXT[failure.code]!, keepOpen: false };
  if (failure.status === 404) return { message: 'No encontramos este apto. Actualizamos la información.', keepOpen: false };
  if (failure.kind === 'forbidden') return { message: 'Esta cuenta no tiene permiso para corregir el apto.', keepOpen: false };
  if (failure.kind === 'unauthenticated') return { message: 'La sesión venció. Es necesario ingresar de nuevo.', keepOpen: false };
  return { message: 'No se pudo confirmar la corrección. Verificá el estado del apto antes de volver a intentar.', keepOpen: false };
}

/**
 * Corrects the three data of an apto with the revision the administrator read. One request at a time; it is aborted when the screen
 * goes away or the session changes. The data typed live only in the dialog's form and in the request: never in the cache, the URL,
 * a storage or a message. Whatever the answer, the apto is read again from the server.
 */
export function useAptoCorrection(athleteId: string) {
  const client = useQueryClient();
  const active = useRef<AbortController | null>(null);
  const [open, setOpen] = useState(false);
  const [pending, setPending] = useState(false);
  const [feedback, setFeedback] = useState<CorrectionFeedback | null>(null);
  const [dialogError, setDialogError] = useState<string | null>(null);

  useLayoutEffect(() => () => active.current?.abort(), [athleteId]);

  const start = () => {
    if (active.current) return;
    setFeedback(null);
    setDialogError(null);
    setOpen(true);
  };
  const close = () => {
    if (!active.current) setOpen(false);
  };

  async function confirm(documentId: string, revision: number | null, data: AptoData) {
    if (active.current || revision === null || !Number.isSafeInteger(revision)) return;
    const currentUser = () => client.getQueryData<AuthUser | null>(sessionKey);
    const user = currentUser();
    if (!user || user.rol !== 'ADMIN' || user.mfaPendiente) return;
    const controller = new AbortController();
    active.current = controller;
    const sameSession = () => {
      const now = currentUser();
      return now?.id === user.id && now.escuelaId === user.escuelaId && now.rol === 'ADMIN' && !now.mfaPendiente;
    };
    const current = () => !controller.signal.aborted && sameSession();
    const unsubscribe = client.getQueryCache().subscribe(() => {
      if (!sameSession()) controller.abort();
    });
    setPending(true);
    setDialogError(null);
    try {
      await correctApto(athleteId, documentId, { versionEsperada: String(revision), data }, () => {
        if (!current()) throw new DOMException('La corrección ya no está activa.', 'AbortError');
      }, controller.signal);
      if (!current()) return;
      setFeedback({ tone: 'success', message: 'Datos corregidos. El apto vuelve a revisión.' });
      setOpen(false);
    } catch (error: unknown) {
      if (!current()) return;
      const failure = correctionFailure(error);
      if (failure.keepOpen) setDialogError(failure.message);
      else {
        setFeedback({ tone: 'error', message: failure.message });
        setOpen(false);
      }
    } finally {
      if (current()) await client.invalidateQueries({ queryKey: aptoKeys.adminAthlete(athleteId) });
      unsubscribe();
      active.current = null;
      if (current()) setPending(false);
    }
  }

  return { open, pending, feedback, dialogError, start, close, confirm };
}
