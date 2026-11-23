import { useLayoutEffect, useRef, useState } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import type { AuthUser } from '@/features/auth/api';
import { sessionKey } from '@/features/auth/session';
import { ApiError } from '@/lib/api-error';
import { reviewAthleteDocument, type DocumentOwnerKind, type DocumentReviewAction } from './api';
import { athleteChecklistKey, athleteDocumentsKey } from './hooks';
import type { AdminDocument } from './schemas';

const REVIEWABLE_TYPES: Record<DocumentOwnerKind, readonly string[]> = {
  deportista: ['DNI_DEPORTISTA_FRENTE', 'DNI_DEPORTISTA_DORSO'],
  tutor: ['DNI_TUTOR_FRENTE', 'DNI_TUTOR_DORSO'],
};

/** Only the DNI of the owner whose screen this is can be reviewed (U19): a type of the other owner never gets the controls. */
export function canReview(document: AdminDocument, action: DocumentReviewAction, kind: DocumentOwnerKind = 'deportista') {
  return document.vigente === true && document.version !== undefined
    && REVIEWABLE_TYPES[kind].includes(document.tipo)
    && (document.estado === 'CARGADO' || (document.estado === 'APROBADO' && action === 'OBSERVADO'));
}

type Selection = { document: AdminDocument; action: DocumentReviewAction };
type Feedback = { tone: 'success' | 'error'; message: string };

export function useDocumentReview(athleteId: string, kind: DocumentOwnerKind = 'deportista') {
  const client = useQueryClient();
  const active = useRef<AbortController | null>(null);
  const [selection, setSelection] = useState<Selection | null>(null);
  const [pending, setPending] = useState(false);
  const [feedback, setFeedback] = useState<Feedback | null>(null);

  // The detail remounts for a different athlete or identity. Abort the old request; the server may already have confirmed it.
  useLayoutEffect(() => () => active.current?.abort(), [athleteId]);

  function open(document: AdminDocument, action: DocumentReviewAction) {
    if (active.current || !canReview(document, action, kind)) return;
    setFeedback(null);
    setSelection({ document, action });
  }

  function close() {
    if (!active.current) setSelection(null);
  }

  async function confirm(motivoObservacion: string) {
    if (!selection || active.current) return;
    const { document, action } = selection;
    if (!canReview(document, action, kind) || document.version === undefined) return;
    const currentUser = () => client.getQueryData<AuthUser | null>(sessionKey);
    const user = currentUser();
    if (!user || user.rol !== 'ADMIN' || user.mfaPendiente) return;

    const controller = new AbortController();
    active.current = controller; // Synchronous: React has not disabled the button yet.
    const sameSession = () => {
      const current = currentUser();
      return current?.id === user.id && current.escuelaId === user.escuelaId && current.rol === 'ADMIN' && !current.mfaPendiente;
    };
    const current = () => !controller.signal.aborted && sameSession();
    const unsubscribe = client.getQueryCache().subscribe(() => {
      if (!sameSession()) controller.abort();
    });
    const key = athleteDocumentsKey(athleteId, kind);
    setPending(true);
    try {
      const saved = await reviewAthleteDocument(athleteId, document.id, {
        estado: action,
        versionEsperada: document.version,
        ...(action === 'OBSERVADO' ? { motivoObservacion } : {}),
      }, () => {
        if (!current()) throw new DOMException('La revisión ya no está activa.', 'AbortError');
      }, controller.signal, kind);
      if (!current()) return;
      client.setQueryData<AdminDocument[]>(key, (documents) => documents?.map((item) => item.id === saved.id ? saved : item));
      setFeedback({ tone: 'success', message: action === 'APROBADO' ? 'Documento aprobado.' : 'Documento observado.' });
    } catch (error: unknown) {
      if (!current()) return;
      const failure = ApiError.from(error);
      setFeedback({ tone: 'error', message: failure.kind === 'conflict'
        ? 'El documento cambió o no pudo revisarse. Verificá su estado actualizado y volvé a decidir.'
        : 'No se pudo confirmar la revisión. Verificá el estado del documento antes de volver a decidir.' });
    } finally {
      if (current()) {
        setSelection(null);
        await client.invalidateQueries({ queryKey: key, exact: true });
        // A review of the athlete's DNI changes the partial checklist (a tutor's does not).
        if (kind === 'deportista') await client.invalidateQueries({ queryKey: athleteChecklistKey(athleteId), exact: true });
      }
      unsubscribe();
      active.current = null;
      if (current()) setPending(false);
    }
  }

  return { selection, pending, feedback, open, close, confirm };
}
