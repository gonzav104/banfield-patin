import { useId } from 'react';
import { Alert } from '@/components/ui/alert';
import { Button } from '@/components/ui/button';
import { cn } from '@/lib/utils';
import type { PreparedDocument } from './image-normalization';
import type { AthleteDocument, DocumentStatus } from './schemas';
import { StatusBadge, type BadgeTone } from './StatusBadge';
import type { UploadStep } from './use-document-upload';

const STATUS: Record<DocumentStatus, { label: string; tone: BadgeTone }> = {
  PENDIENTE: { label: 'Pendiente', tone: 'neutral' },
  CARGADO: { label: 'En revisión', tone: 'neutral' },
  APROBADO: { label: 'Aprobado', tone: 'positive' },
  OBSERVADO: { label: 'Observado', tone: 'warning' },
  VENCIDO: { label: 'Vencido', tone: 'warning' },
};

const dateFormat = new Intl.DateTimeFormat('es-AR', { dateStyle: 'medium', timeZone: 'America/Argentina/Buenos_Aires' });
const uploadedOn = (iso: string) => {
  const date = new Date(iso);
  return Number.isNaN(date.getTime()) ? null : dateFormat.format(date);
};

export const LOW_RESOLUTION_WARNING =
  'La imagen tiene poca resolución y puede no leerse bien. Si podés, sacá una foto más cerca y con buena luz.';
export const CHECK_BEFORE_SENDING = 'Revisá que se lean bien todos los datos antes de enviar.';

/** The upload controls of an empty slot, or null when uploads are not offered (disabled, unknown or not loaded). */
export interface SlotUpload {
  state: UploadStep;
  choose: (file: File) => void;
  send: (prepared: PreparedDocument) => void;
  discard: () => void;
}

const pickerLabel =
  'inline-flex min-h-11 cursor-pointer items-center justify-center rounded-lg px-4 text-sm font-semibold peer-focus-visible:outline-2 peer-focus-visible:outline-offset-2 peer-focus-visible:outline-brand-700';

export function Pickers({ title, onFile }: { title: string; onFile: (file: File) => void }) {
  const id = useId();
  const handle = (event: React.ChangeEvent<HTMLInputElement>) => {
    const file = event.target.files?.[0];
    event.target.value = ''; // the browser does not keep a reference to the chosen file in the input
    if (file) onFile(file);
  };
  return (
    <div className="flex flex-wrap gap-2">
      <span>
        <input id={`${id}-camera`} type="file" accept="image/*" capture="environment" className="peer sr-only" onChange={handle} />
        <label htmlFor={`${id}-camera`} className={cn(pickerLabel, 'bg-brand-700 text-white hover:bg-brand-800')}>
          Sacar foto<span className="sr-only"> del {title}</span>
        </label>
      </span>
      <span>
        <input id={`${id}-file`} type="file" accept="image/*,application/pdf" className="peer sr-only" onChange={handle} />
        <label htmlFor={`${id}-file`} className={cn(pickerLabel, 'border border-line bg-white text-ink hover:bg-brand-50')}>
          Elegir archivo<span className="sr-only"> para el {title}</span>
        </label>
      </span>
    </div>
  );
}

export function Preview({ title, prepared, previewUrl }: { title: string; prepared: PreparedDocument; previewUrl: string | null }) {
  return (
    <div className="space-y-2">
      {previewUrl ? (
        <img src={previewUrl} alt={`Vista previa del ${title}`} className="max-h-72 w-full rounded-lg border border-line object-contain" />
      ) : (
        <p className="text-sm">PDF listo para enviar ({Math.max(1, Math.round(prepared.blob.size / 1000))} KB).</p>
      )}
      {prepared.lowResolution ? <Alert tone="warning">{LOW_RESOLUTION_WARNING}</Alert> : null}
    </div>
  );
}

/**
 * One DNI side: its current state as the server reports it and, when the slot is empty (or its document is OBSERVED and
 * can be replaced) and uploads are offered, the upload flow (choose → preview → send → retry). Presentational: the attempt is owned by `useDocumentUpload`.
 */
export function DniSlot(props: {
  title: string;
  document: AthleteDocument | null;
  upload: SlotUpload | null;
  unavailableText: string | null;
}) {
  const { title, document, upload, unavailableText } = props;
  const headingId = useId();
  const status = document ? STATUS[document.estado] : { label: 'Falta', tone: 'neutral' as const };
  const date = document ? uploadedOn(document.creadoEn) : null;
  const state = upload?.state;

  return (
    <article aria-labelledby={headingId} className="space-y-3 border-t border-line pt-4 first:border-t-0 first:pt-0">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h3 id={headingId} className="font-bold">
          {title}
        </h3>
        <StatusBadge tone={status.tone} srLabel="Estado: ">
          {status.label}
        </StatusBadge>
      </div>

      {document && date ? <p className="text-sm text-muted">Cargado el {date}.</p> : null}
      {document?.estado === 'OBSERVADO' ? (
        <Alert tone="warning">
          <p>
            <span className="font-semibold">Motivo:</span> {document.motivoObservacion ?? 'Sin detalle.'}
          </p>
          {upload ? (
            state?.step === 'sent' ? null : <p className="mt-1">Subí un archivo nuevo para reemplazarlo.</p>
          ) : (
            <p className="mt-1">Comunicate con el club para resolverlo.</p>
          )}
        </Alert>
      ) : null}

      {!document && !upload && unavailableText ? <p className="text-sm text-muted">{unavailableText}</p> : null}

      {/* Shown even when the list now reports a document (for example after a 409 refreshed it). */}
      {state?.step === 'idle' && state.notice ? <Alert tone="error">{state.notice}</Alert> : null}
      {(!document || document.estado === 'OBSERVADO') && upload && state ? (
        <div className="space-y-3">
          {state.step === 'idle' ? <Pickers title={title} onFile={upload.choose} /> : null}
          {state.step === 'preparing' ? <p role="status" className="text-sm text-muted">Preparando la imagen…</p> : null}
          {state.step === 'ready' ? (
            <>
              <Preview title={title} prepared={state.prepared} previewUrl={state.previewUrl} />
              <p className="text-sm text-muted">{CHECK_BEFORE_SENDING}</p>
              <div className="flex flex-wrap gap-2">
                <Button onClick={() => upload.send(state.prepared)}>Enviar</Button>
                <Button variant="outline" onClick={upload.discard}>
                  Elegir otro
                </Button>
              </div>
            </>
          ) : null}
          {state.step === 'sending' ? (
            <>
              <Preview title={title} prepared={state.prepared} previewUrl={state.previewUrl} />
              <p role="status" className="text-sm font-semibold">
                Enviando…{state.progress !== null ? ` ${Math.round(state.progress * 100)} %` : ''}
              </p>
            </>
          ) : null}
          {state.step === 'failed' ? (
            <>
              <Preview title={title} prepared={state.prepared} previewUrl={state.previewUrl} />
              <Alert tone="error">{state.failure.message}</Alert>
              <div className="flex flex-wrap gap-2">
                <Button onClick={() => upload.send(state.prepared)}>Reintentar</Button>
                <Button variant="outline" onClick={upload.discard}>
                  Descartar
                </Button>
              </div>
            </>
          ) : null}
        </div>
      ) : null}
      {state?.step === 'sent' ? <Alert tone="success">Documento enviado. El club lo va a revisar.</Alert> : null}
    </article>
  );
}
