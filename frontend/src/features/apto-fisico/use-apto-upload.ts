import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useCallback, useEffect, useRef, useState } from 'react';
import { familyKeys } from '@/features/familia/hooks';
import { identityKey, type FamilyIdentity } from '@/features/familia/identity';
import { prepareDocument, type PrepareFailure, type PreparedDocument } from '@/features/familia/image-normalization';
import { newIdempotencyKey } from '@/features/familia/use-document-upload';
import { replaceApto, uploadApto } from './api';
import { aptoDataSignature, type AptoData } from './form';
import { aptoUploadFailure, type AptoUploadFailure } from './upload-outcome';

/**
 * One apto upload attempt. The attempt (Idempotency-Key + the EXACT normalized bytes + the declared data + the season and the
 * document it replaces) lives only in this component's memory: never in the query cache, the URL or the browser storage, and never
 * in a log. A retry re-sends the same Blob with the same data and the same key, so the server returns what it already stored.
 * Changing the file, the data, the season or the replaced document always starts a NEW key (the server includes them in the identity).
 */
export type AptoUploadStep =
  | { step: 'idle'; notice: string | null }
  | { step: 'preparing' }
  | { step: 'ready'; prepared: PreparedDocument; previewUrl: string | null; notice: string | null }
  | { step: 'sending'; prepared: PreparedDocument; previewUrl: string | null; data: AptoData; progress: number | null }
  | { step: 'failed'; prepared: PreparedDocument; previewUrl: string | null; data: AptoData; failure: AptoUploadFailure }
  | { step: 'sent' };

const PREPARE_MESSAGES: Record<PrepareFailure, string> = {
  unsupported: 'Elegí una foto (JPG o PNG) o un PDF.',
  'too-large': 'El archivo es demasiado grande. Probá con una foto nueva o con un PDF más liviano.',
  'decode-failed': 'No pudimos abrir esta imagen en este navegador (por ejemplo, una foto en formato HEIC). Usá «Sacar foto» o elegí un JPG, PNG o PDF.',
  'encode-failed': 'No pudimos preparar la imagen para enviarla. Probá de nuevo o elegí un PDF.',
};

interface Options {
  identity: FamilyIdentity;
  athleteId: string;
  seasonId: string;
  maxBytes: number;
  /** The id of the OBSERVED apto this attempt replaces, or null for a first upload of the season. */
  replaces: string | null;
}

export function useAptoUpload({ identity, athleteId, seasonId, maxBytes, replaces }: Options) {
  const queryClient = useQueryClient();
  const [state, setState] = useState<AptoUploadStep>({ step: 'idle', notice: null });
  const attempt = useRef<{ key: string; prepared: PreparedDocument; replaces: string | null; seasonId: string; signature: string } | null>(null);
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

  const mutation = useMutation({
    mutationFn: (input: { prepared: PreparedDocument; data: AptoData; idempotencyKey: string; onProgress: (fraction: number) => void }) =>
      replaces
        ? replaceApto({ athleteId, documentId: replaces, ...input })
        : uploadApto({ athleteId, seasonId, ...input }),
  });

  const drop = useCallback(
    (notice: string | null) => {
      releasePreview();
      attempt.current = null;
      setState({ step: 'idle', notice });
    },
    [releasePreview],
  );

  // Another season means what was chosen belongs to something else: nothing is carried over. A different OBSERVED document does the
  // same, but going back to «no observed document» (the replacement was accepted) keeps the confirmation on screen.
  const lastSeason = useRef(seasonId);
  const lastReplaces = useRef(replaces);
  const sending = mutation.isPending;
  useEffect(() => {
    if (sending) return; // re-evaluated when the request in flight settles
    const seasonChanged = seasonId !== lastSeason.current;
    const replacesChanged = replaces !== lastReplaces.current;
    lastSeason.current = seasonId;
    lastReplaces.current = replaces;
    if (seasonChanged || (replacesChanged && replaces !== null)) drop(null);
  }, [seasonId, replaces, sending, drop]);

  const refresh = useCallback(async () => {
    await queryClient.invalidateQueries({ queryKey: ['familia', identityKey(identity), 'apto', athleteId] });
    await queryClient.invalidateQueries({ queryKey: familyKeys.documents(identity, athleteId) });
  }, [athleteId, identity, queryClient]);

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
      setState({ step: 'ready', prepared: result.prepared, previewUrl: previewUrl.current, notice: null });
    },
    [maxBytes, releasePreview],
  );

  const send = useCallback(
    async (prepared: PreparedDocument, data: AptoData) => {
      const signature = aptoDataSignature(data);
      const current = attempt.current;
      // The key is born on the first send of THESE bytes with THESE data for THIS season/document and is reused, unchanged, by every retry.
      if (!current || current.prepared !== prepared || current.replaces !== replaces || current.seasonId !== seasonId || current.signature !== signature) {
        attempt.current = { key: newIdempotencyKey(), prepared, replaces, seasonId, signature };
      }
      const { key } = attempt.current!;
      setState({ step: 'sending', prepared, previewUrl: previewUrl.current, data, progress: null });
      try {
        await mutation.mutateAsync({
          prepared,
          data,
          idempotencyKey: key,
          onProgress: (fraction) => {
            if (mounted.current) setState((s) => (s.step === 'sending' ? { ...s, progress: fraction } : s));
          },
        });
        if (!mounted.current) return;
        releasePreview();
        attempt.current = null;
        setState({ step: 'sent' });
        await refresh();
      } catch (error) {
        if (!mounted.current) return;
        const failure = aptoUploadFailure(error, maxBytes, replaces !== null);
        if (failure.action === 'retry-same') {
          setState({ step: 'failed', prepared, previewUrl: previewUrl.current, data, failure });
          return;
        }
        if (failure.action === 'fix-data') {
          // The file stays; the data are edited and the next send gets a new key.
          attempt.current = null;
          setState({ step: 'ready', prepared, previewUrl: previewUrl.current, notice: failure.message });
          return;
        }
        drop(failure.message);
        if (failure.action === 'refresh') await refresh();
      }
    },
    [drop, maxBytes, mutation, refresh, releasePreview, replaces, seasonId],
  );

  return { state, choose, send, discard: () => drop(null), editAfterFailure: (s: Extract<AptoUploadStep, { step: 'failed' }>) => setState({ step: 'ready', prepared: s.prepared, previewUrl: s.previewUrl, notice: null }) };
}

