import { useId, useState } from 'react';
import { Alert } from '@/components/ui/alert';
import { Button } from '@/components/ui/button';
import { DocumentPreview } from '@/features/admin-deportistas/AthleteDocumentsView';
import { DocumentReviewDialog } from '@/features/admin-deportistas/DocumentReviewDialog';
import type { AdminDocument } from '@/features/admin-deportistas/schemas';
import { canReview, useDocumentReview } from '@/features/admin-deportistas/use-document-review';
import { useDocumentViewer } from '@/features/admin-deportistas/use-document-viewer';
import { AptoCorrectionDialog } from './AptoCorrectionDialog';
import { AptoSummary } from './AptoSummary';
import { useAdminApto, useAdminSeasons } from './hooks';
import { APTO_TITLE, SEASON_LABEL } from './messages';
import type { AptoDocument } from './schemas';
import { useAptoCorrection } from './use-apto-correction';

export const ADMIN_APTO_SECTION_ID = 'apto-fisico-admin';

/** The apto document as the review and the viewer hooks know a document. The version stays undefined when it is not a safe integer. */
function asAdminDocument(doc: AptoDocument): AdminDocument {
  return {
    id: doc.id,
    tipo: 'APTO_FISICO',
    estado: doc.estado,
    creadoEn: doc.cargadoEn,
    vigente: true,
    ...(doc.revision !== null && Number.isSafeInteger(doc.revision) ? { version: String(doc.revision) } : {}),
    ...(doc.motivoObservacion ? { motivoObservacion: doc.motivoObservacion } : {}),
  };
}

/**
 * «Apto físico» in the ADMIN athlete detail: the derived state of one season with the data the family declared, the audited
 * opening of the file, the review (approve / observe) and the correction of the data. Correcting an APPROVED apto withdraws its
 * approval, and the screen says so before it is confirmed.
 */
export function AdminAptoSection({ athleteId }: { athleteId: string }) {
  const titleId = 'admin-apto-title';
  const selectId = useId();
  const seasons = useAdminSeasons();
  const [chosen, setChosen] = useState<string | null>(null);
  const options = seasons.data ?? [];
  const seasonId = chosen && options.some((s) => s.id === chosen) ? chosen : (options.find((s) => s.activa) ?? options[0])?.id ?? null;
  const apto = useAdminApto(athleteId, seasonId);
  const viewer = useDocumentViewer(athleteId);
  const review = useDocumentReview(athleteId);
  const correction = useAptoCorrection(athleteId);
  const doc = apto.data?.documento ?? null;
  const adminDoc = doc ? asAdminDocument(doc) : null;
  const busy = review.pending || correction.pending || apto.isFetching;
  const correctable = !!doc && doc.revision !== null && Number.isSafeInteger(doc.revision) && (doc.estado === 'CARGADO' || doc.estado === 'APROBADO');

  return (
    <section id={ADMIN_APTO_SECTION_ID} tabIndex={-1} aria-labelledby={titleId} className="space-y-3 rounded-2xl border border-line bg-white p-5 outline-none">
      <h2 id={titleId} className="text-base font-bold">
        {APTO_TITLE}
      </h2>
      {seasons.isPending ? <p role="status" className="text-sm text-muted">Cargando el apto físico…</p> : null}
      {seasons.isError ? (
        <div className="space-y-3">
          <Alert tone="error">No se pudieron cargar las temporadas.</Alert>
          <Button variant="outline" onClick={() => void seasons.refetch()}>Reintentar</Button>
        </div>
      ) : null}
      {seasons.data && options.length === 0 ? <p className="text-sm">La escuela todavía no tiene temporadas.</p> : null}
      {options.length > 0 ? (
        <div className="space-y-1">
          <label htmlFor={selectId} className="block text-sm font-semibold">{SEASON_LABEL}</label>
          <select id={selectId} value={seasonId ?? ''} onChange={(event) => setChosen(event.target.value)} className="min-h-11 rounded-md border border-line bg-white px-3 text-base">
            {options.map((season) => (
              <option key={season.id} value={season.id}>{season.anio}{season.activa ? ' (actual)' : ''}</option>
            ))}
          </select>
        </div>
      ) : null}
      {seasonId && apto.isPending ? <p role="status" className="text-sm text-muted">Cargando el apto físico…</p> : null}
      {seasonId && apto.isError ? (
        <div className="space-y-3">
          <Alert tone="error">No se pudo cargar el apto físico.</Alert>
          <Button variant="outline" onClick={() => void apto.refetch()}>Reintentar</Button>
        </div>
      ) : null}
      {review.feedback ? <Alert tone={review.feedback.tone} role={review.feedback.tone === 'success' ? 'status' : 'alert'}>{review.feedback.message}</Alert> : null}
      {correction.feedback ? <Alert tone={correction.feedback.tone} role={correction.feedback.tone === 'success' ? 'status' : 'alert'}>{correction.feedback.message}</Alert> : null}
      {apto.data ? <AptoSummary apto={apto.data} /> : null}
      {doc && adminDoc ? (
        <div className="flex flex-wrap gap-2">
          <Button variant="outline" disabled={viewer.state.status === 'loading'} onClick={() => viewer.open(adminDoc)} aria-label="Abrir documento: Apto físico">Abrir documento</Button>
          {canReview(adminDoc, 'APROBADO') ? <Button disabled={busy} onClick={() => review.open(adminDoc, 'APROBADO')} aria-label="Aprobar documento: Apto físico">Aprobar</Button> : null}
          {canReview(adminDoc, 'OBSERVADO') ? <Button variant="outline" disabled={busy} onClick={() => review.open(adminDoc, 'OBSERVADO')} aria-label="Observar documento: Apto físico">Observar</Button> : null}
          {correctable ? <Button variant="outline" disabled={busy} onClick={correction.start} aria-label="Corregir los datos del apto físico">Corregir datos</Button> : null}
        </div>
      ) : null}
      {viewer.state.status === 'loading' ? <p role="status" className="text-sm text-muted">Abriendo el documento…</p> : null}
      {viewer.state.status !== 'idle' && viewer.state.status !== 'loading' ? <DocumentPreview state={viewer.state} onClose={viewer.close} onDownload={viewer.downloadPdf} /> : null}
      {review.selection ? <DocumentReviewDialog document={review.selection.document} action={review.selection.action} pending={review.pending} onCancel={review.close} onConfirm={review.confirm} /> : null}
      {correction.open && doc ? (
        <AptoCorrectionDialog document={doc} pending={correction.pending} error={correction.dialogError} onCancel={correction.close} onConfirm={(data) => correction.confirm(doc.id, doc.revision, data)} />
      ) : null}
    </section>
  );
}
