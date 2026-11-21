import { useCallback, useEffect, useRef, useState } from 'react';
import { ApiError } from '@/lib/api-error';
import { fetchAthleteDocumentFile } from './api';
import { DOCUMENT_UNSUPPORTED, documentFileErrorMessage } from './messages';
import type { AdminDocument } from './schemas';

export type DocumentViewerState =
  | { status: 'idle' }
  | { status: 'loading'; document: AdminDocument }
  | { status: 'image'; document: AdminDocument; url: string }
  | { status: 'pdf'; document: AdminDocument; fileName: string }
  | { status: 'error'; document: AdminDocument; message: string };

export interface DocumentViewer {
  state: DocumentViewerState;
  open: (document: AdminDocument) => void;
  close: () => void;
  downloadPdf: () => void;
}

/**
 * Opens ONE document at a time. The file lives only in this hook (a ref for the PDF bytes, an object URL for the image on screen):
 * never in the query cache, the URL, a storage or the console. Every object URL is revoked on close, when another document is
 * opened, right after a PDF download starts, and on unmount. A response that arrives after the viewer moved on (closed, another
 * document, unmounted) is dropped without creating a URL. Nothing is retried: each successful read is an audited access.
 */
export function useDocumentViewer(athleteId: string): DocumentViewer {
  const [state, setState] = useState<DocumentViewerState>({ status: 'idle' });
  const imageUrl = useRef<string | null>(null);
  const pdf = useRef<Blob | null>(null);
  const pendingDownloads = useRef(new Map<string, ReturnType<typeof setTimeout>>());
  const attempt = useRef(0);

  const release = useCallback(() => {
    if (imageUrl.current) URL.revokeObjectURL(imageUrl.current);
    imageUrl.current = null;
    pdf.current = null;
  }, []);

  const revokePendingDownloads = useCallback(() => {
    for (const [url, timer] of pendingDownloads.current) {
      clearTimeout(timer);
      URL.revokeObjectURL(url);
    }
    pendingDownloads.current.clear();
  }, []);

  useEffect(
    () => () => {
      attempt.current += 1; // a response still in flight is dropped
      release();
      revokePendingDownloads();
    },
    [release, revokePendingDownloads],
  );

  const open = useCallback(
    (document: AdminDocument) => {
      release();
      const current = ++attempt.current;
      setState({ status: 'loading', document });
      fetchAthleteDocumentFile(athleteId, document.id).then(
        (file) => {
          if (current !== attempt.current) return;
          if (file.kind === 'image') {
            const url = URL.createObjectURL(file.blob);
            imageUrl.current = url;
            setState({ status: 'image', document, url });
          } else if (file.kind === 'pdf') {
            pdf.current = file.blob;
            setState({ status: 'pdf', document, fileName: file.fileName });
          } else {
            setState({ status: 'error', document, message: DOCUMENT_UNSUPPORTED });
          }
        },
        (error: unknown) => {
          if (current !== attempt.current) return;
          setState({ status: 'error', document, message: documentFileErrorMessage(ApiError.from(error)) });
        },
      );
    },
    [athleteId, release],
  );

  const close = useCallback(() => {
    attempt.current += 1;
    release();
    setState({ status: 'idle' });
  }, [release]);

  const downloadPdf = useCallback(() => {
    const blob = pdf.current;
    if (!blob || state.status !== 'pdf') return;
    const url = URL.createObjectURL(blob);
    const link = window.document.createElement('a');
    link.href = url;
    link.download = state.fileName;
    link.rel = 'noopener';
    link.hidden = true;
    window.document.body.appendChild(link);
    link.click();
    link.remove();
    // Revoked as soon as the browser took the download (the next task); on unmount it is revoked at once.
    const timer = setTimeout(() => {
      pendingDownloads.current.delete(url);
      URL.revokeObjectURL(url);
    }, 0);
    pendingDownloads.current.set(url, timer);
  }, [state]);

  return { state, open, close, downloadPdf };
}
