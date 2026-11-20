import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useCallback, useEffect, useRef, useState } from 'react';
import { uploadAthleteDocument, type DniType } from './api';
import { familyKeys } from './hooks';
import type { FamilyIdentity } from './identity';
import { prepareDocument, type PrepareFailure, type PreparedDocument } from './image-normalization';
import { uploadFailure, type UploadFailure } from './upload-outcome';

/**
 * One upload attempt of one DNI side. The attempt (Idempotency-Key + the EXACT normalized bytes) lives only in this
 * component's memory: never in the query cache, the URL or the browser storage. A retry re-sends the same Blob with the
 * same key, so the server can answer with the document it already stored instead of storing it twice (re-encoding again
 * could produce different bytes and the server would reject the key).
 */
export type UploadStep =
  | { step: 'idle'; notice: string | null }
  | { step: 'preparing' }
  | { step: 'ready'; prepared: PreparedDocument; previewUrl: string | null }
  | { step: 'sending'; prepared: PreparedDocument; previewUrl: string | null; progress: number | null }
  | { step: 'failed'; prepared: PreparedDocument; previewUrl: string | null; failure: UploadFailure }
  | { step: 'sent' };

const PREPARE_MESSAGES: Record<PrepareFailure, string> = {
  unsupported: 'Elegí una foto (JPG o PNG) o un PDF.',
  'too-large': 'El archivo es demasiado grande. Probá con una foto nueva o con un PDF más liviano.',
  'decode-failed':
    'No pudimos abrir esta imagen en este navegador (por ejemplo, una foto en formato HEIC). Usá «Sacar foto» o elegí un JPG, PNG o PDF.',
  'encode-failed': 'No pudimos preparar la imagen para enviarla. Probá de nuevo o elegí un PDF.',
};

/** RFC 4122 v4. `crypto.randomUUID` needs a secure context; outside one, the same format from `getRandomValues`. */
export function newIdempotencyKey(): string {
  if (typeof crypto.randomUUID === 'function') return crypto.randomUUID();
  const bytes = crypto.getRandomValues(new Uint8Array(16));
  bytes[6] = (bytes[6]! & 0x0f) | 0x40;
  bytes[8] = (bytes[8]! & 0x3f) | 0x80;
  const hex = [...bytes].map((b) => b.toString(16).padStart(2, '0')).join('');
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}

export function useDocumentUpload(options: { identity: FamilyIdentity; athleteId: string; tipo: DniType; maxBytes: number }) {
  const { identity, athleteId, tipo, maxBytes } = options;
  const queryClient = useQueryClient();
  const [state, setState] = useState<UploadStep>({ step: 'idle', notice: null });
  const attempt = useRef<{ key: string; prepared: PreparedDocument } | null>(null);
  const previewUrl = useRef<string | null>(null);
  const mounted = useRef(true);

  const releasePreview = useCallback(() => {
    if (previewUrl.current) URL.revokeObjectURL(previewUrl.current);
    previewUrl.current = null;
  }, []);

  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
      releasePreview();
      attempt.current = null;
    };
  }, [releasePreview]);

  const drop = useCallback(
    (notice: string | null) => {
      releasePreview();
      attempt.current = null;
      setState({ step: 'idle', notice });
    },
    [releasePreview],
  );

  const choose = useCallback(
    async (file: File) => {
      releasePreview();
      attempt.current = null;
      setState({ step: 'preparing' });
      const result = await prepareDocument(file, maxBytes);
      if (!mounted.current) return;
      if (!result.ok) {
        setState({ step: 'idle', notice: PREPARE_MESSAGES[result.reason] });
        return;
      }
      previewUrl.current = result.prepared.kind === 'image' ? URL.createObjectURL(result.prepared.blob) : null;
      setState({ step: 'ready', prepared: result.prepared, previewUrl: previewUrl.current });
    },
    [maxBytes, releasePreview],
  );

  const mutation = useMutation({ mutationFn: uploadAthleteDocument });

  const send = useCallback(
    async (prepared: PreparedDocument) => {
      // The key is born on the first send of THESE bytes and is reused, unchanged, by every retry of them.
      if (!attempt.current || attempt.current.prepared !== prepared) attempt.current = { key: newIdempotencyKey(), prepared };
      const { key } = attempt.current;
      setState({ step: 'sending', prepared, previewUrl: previewUrl.current, progress: null });
      try {
        await mutation.mutateAsync({
          athleteId,
          tipo,
          prepared,
          idempotencyKey: key,
          onProgress: (fraction) => {
            if (mounted.current) setState((s) => (s.step === 'sending' ? { ...s, progress: fraction } : s));
          },
        });
        if (!mounted.current) return;
        releasePreview();
        attempt.current = null;
        setState({ step: 'sent' });
        await queryClient.invalidateQueries({ queryKey: familyKeys.documents(identity, athleteId) });
      } catch (error) {
        if (!mounted.current) return;
        const failure = uploadFailure(error, maxBytes);
        if (failure.action === 'retry-same') {
          setState({ step: 'failed', prepared, previewUrl: previewUrl.current, failure });
          return;
        }
        drop(failure.message);
        if (failure.action === 'refresh') await queryClient.invalidateQueries({ queryKey: familyKeys.documents(identity, athleteId) });
      }
    },
    [athleteId, drop, identity, maxBytes, mutation, queryClient, releasePreview, tipo],
  );

  return { state, choose, send, discard: () => drop(null) };
}
