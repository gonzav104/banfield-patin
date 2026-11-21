import { useEffect, useRef } from 'react';
import { Alert } from '@/components/ui/alert';
import { Button } from '@/components/ui/button';
import { StatusBadge, type BadgeTone } from '@/features/familia/StatusBadge';
import type { DocumentStatus } from '@/features/familia/schemas';
import { DOCUMENT_LOADING, DOCUMENTS_EMPTY, DOCUMENTS_LOADING, DOCUMENTS_NOTE, DOCUMENTS_TITLE, documentTypeLabel, PDF_READY, TUTOR_DOCUMENTS_EMPTY } from './messages';
import type { DocumentReviewAction } from './api';
import { canReview } from './use-document-review';
import type { AdminDocument } from './schemas';
import type { DocumentViewerState } from './use-document-viewer';

const STATUS: Record<DocumentStatus, { label: string; tone: BadgeTone }> = {
  PENDIENTE: { label: 'Pendiente', tone: 'neutral' },
  CARGADO: { label: 'Cargado', tone: 'neutral' },
  APROBADO: { label: 'Aprobado', tone: 'positive' },
  OBSERVADO: { label: 'Observado', tone: 'warning' },
  VENCIDO: { label: 'Vencido', tone: 'warning' },
};

const dateFormat = new Intl.DateTimeFormat('es-AR', { dateStyle: 'medium', timeZone: 'America/Argentina/Buenos_Aires' });
/** The upload date in the school's time zone (null when the server sent something that is not a date). */
export function uploadDate(iso: string): string | null {
  const date = new Date(iso);
  return Number.isNaN(date.getTime()) ? null : dateFormat.format(date);
}

function DocumentRow({ document, busy, onOpen, rowRef, reviewDisabled, onReview, owner }: {
  document: AdminDocument; busy: boolean; onOpen: () => void; rowRef: (node: HTMLLIElement | null) => void;
  reviewDisabled: boolean; onReview: (action: DocumentReviewAction) => void; owner: 'deportista' | 'tutor';
}) {
  const label = documentTypeLabel(document.tipo);
  const status = STATUS[document.estado];
  const date = uploadDate(document.creadoEn);
  const reason = document.estado === 'OBSERVADO' ? document.motivoObservacion?.trim() : null;
  return (
    <li ref={rowRef} className="space-y-2 rounded-xl border border-line p-4">
      <div className="flex flex-wrap items-center gap-2">
        <h3 className="min-w-0 break-words text-sm font-bold">{label}</h3>
        <StatusBadge tone={status.tone} srLabel="Estado: ">
          {status.label}
        </StatusBadge>
      </div>
      <p className="text-sm text-muted">Fecha de carga: {date ?? 'No informada'}</p>
      {reason ? <p className="break-words text-sm">Motivo de la observación: {reason}</p> : null}
      <div className="flex flex-wrap gap-2">
        <Button variant="outline" disabled={busy} onClick={onOpen} aria-label={`Abrir documento: ${label}`}>
          Abrir documento
        </Button>
        {canReview(document, 'APROBADO', owner) ? (
          <Button disabled={reviewDisabled} onClick={() => onReview('APROBADO')} aria-label={`Aprobar documento: ${label}`}>Aprobar</Button>
        ) : null}
        {canReview(document, 'OBSERVADO', owner) ? (
          <Button variant="outline" disabled={reviewDisabled} onClick={() => onReview('OBSERVADO')} aria-label={`Observar documento: ${label}`}>Observar</Button>
        ) : null}
      </div>
    </li>
  );
}

function Preview({ state, onClose, onDownload }: { state: Exclude<DocumentViewerState, { status: 'idle' | 'loading' }>; onClose: () => void; onDownload: () => void }) {
  const label = documentTypeLabel(state.document.tipo);
  const region = useRef<HTMLDivElement>(null);
  // Focus follows what was just opened (or what failed), so a keyboard or screen-reader user lands on it.
  useEffect(() => region.current?.focus(), [state]);
  return (
    <div ref={region} tabIndex={-1} role="region" aria-label={`Documento abierto: ${label}`} className="space-y-3 rounded-xl border border-line p-4 outline-none focus-visible:ring-2 focus-visible:ring-brand-700">
      <h3 className="break-words text-sm font-bold">Documento abierto: {label}</h3>
      {state.status === 'error' ? <Alert tone="error">{state.message}</Alert> : null}
      {state.status === 'image' ? <img src={state.url} alt={`Documento: ${label}`} className="max-h-[70vh] w-full rounded-lg border border-line object-contain" /> : null}
      {state.status === 'pdf' ? (
        <div className="space-y-2">
          <p className="text-sm">{PDF_READY}</p>
          <Button onClick={onDownload}>Descargar PDF</Button>
        </div>
      ) : null}
      <div>
        <Button variant="outline" onClick={onClose}>
          Cerrar
        </Button>
      </div>
    </div>
  );
}

export interface AthleteDocumentsViewProps {
  documents: AdminDocument[] | undefined;
  loading: boolean;
  listError: string | null;
  onRetryList: () => void;
  reviewDisabled: boolean;
  reviewFeedback: { tone: 'success' | 'error'; message: string } | null;
  onReview: (document: AdminDocument, action: DocumentReviewAction) => void;
  viewer: DocumentViewerState;
  onOpen: (document: AdminDocument) => void;
  onClose: () => void;
  onDownload: () => void;
  /** Whose documents these are: only the text of the empty list changes (U19). */
  owner?: 'deportista' | 'tutor';
}

/** Presentational: the list of current documents with one «Abrir documento» per row, and the single open document. */
export function AthleteDocumentsView({ documents, loading, listError, onRetryList, viewer, onOpen, onClose, onDownload,
  reviewDisabled, reviewFeedback, onReview, owner = 'deportista' }: AthleteDocumentsViewProps) {
  const rows = useRef(new Map<string, HTMLLIElement>());
  const lastOpened = useRef<string | null>(null);
  const busy = viewer.status === 'loading';

  useEffect(() => {
    if (viewer.status !== 'idle') {
      lastOpened.current = viewer.document.id;
    } else if (lastOpened.current) {
      rows.current.get(lastOpened.current)?.querySelector('button')?.focus(); // back to the button that opened the closed document
      lastOpened.current = null;
    }
  }, [viewer]);

  return (
    <section aria-label={DOCUMENTS_TITLE} className="space-y-3 rounded-2xl border border-line bg-white p-5">
      <h2 className="text-base font-bold">{DOCUMENTS_TITLE}</h2>
      <p className="text-sm text-muted">{DOCUMENTS_NOTE}</p>
      {reviewFeedback ? (
        <Alert tone={reviewFeedback.tone} role={reviewFeedback.tone === 'success' ? 'status' : 'alert'}>{reviewFeedback.message}</Alert>
      ) : null}
      {loading ? (
        <p role="status" className="text-sm text-muted">
          {DOCUMENTS_LOADING}
        </p>
      ) : null}
      {listError ? (
        <div className="space-y-3">
          <Alert tone="error">{listError}</Alert>
          <Button variant="outline" onClick={onRetryList}>
            Reintentar
          </Button>
        </div>
      ) : null}
      {documents && documents.length === 0 ? <p className="text-sm">{owner === 'tutor' ? TUTOR_DOCUMENTS_EMPTY : DOCUMENTS_EMPTY}</p> : null}
      {documents && documents.length > 0 ? (
        <ul className="space-y-3">
          {documents.map((document) => (
            <DocumentRow
              key={document.id}
              document={document}
              busy={busy}
              reviewDisabled={reviewDisabled}
              owner={owner}
              onReview={(action) => onReview(document, action)}
              onOpen={() => onOpen(document)}
              rowRef={(node) => {
                if (node) rows.current.set(document.id, node);
                else rows.current.delete(document.id);
              }}
            />
          ))}
        </ul>
      ) : null}
      {viewer.status === 'loading' ? (
        <p role="status" className="text-sm text-muted">
          {DOCUMENT_LOADING}
        </p>
      ) : null}
      {viewer.status !== 'idle' && viewer.status !== 'loading' ? <Preview state={viewer} onClose={onClose} onDownload={onDownload} /> : null}
    </section>
  );
}
