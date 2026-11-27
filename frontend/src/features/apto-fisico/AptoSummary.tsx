import { Alert } from '@/components/ui/alert';
import { StatusBadge } from '@/features/familia/StatusBadge';
import { formatDay, NOTICE_TEXT, OBSERVED_BY_OTHER, OTHER_FAMILY_DATA, STATE_LABEL, STATE_TONE, validityText } from './messages';
import type { Apto } from './schemas';

const uploadedFormat = new Intl.DateTimeFormat('es-AR', { dateStyle: 'medium', timeZone: 'America/Argentina/Buenos_Aires' });
const uploadedOn = (iso: string) => {
  const date = new Date(iso);
  return Number.isNaN(date.getTime()) ? null : uploadedFormat.format(date);
};

/** Known notice codes only, in the order the server sent them; the family also learns an observed apto of another family needs replacing. */
export function visibleNotices(apto: Apto): string[] {
  const texts = apto.avisos.flatMap((code) => (Object.hasOwn(NOTICE_TEXT, code) ? [NOTICE_TEXT[code]!] : []));
  const doc = apto.documento;
  if (doc && !doc.datosVisibles && apto.estado === 'OBSERVADO' && !texts.includes(OBSERVED_BY_OTHER)) texts.push(OBSERVED_BY_OTHER);
  return texts;
}

/**
 * The state of the apto of one season, as the server derived it, for the family and for ADMIN. Presentational. When the current
 * version belongs to another family (`datosVisibles` false) only the state is shown: there is nothing else to show, and nothing
 * hidden is ever requested or reconstructed.
 */
export function AptoSummary({ apto }: { apto: Apto }) {
  const doc = apto.documento ?? null;
  const notices = visibleNotices(apto);
  const date = doc ? uploadedOn(doc.cargadoEn) : null;
  return (
    <div className="space-y-3">
      <div className="flex flex-wrap items-center gap-2">
        <StatusBadge tone={STATE_TONE[apto.estado]} srLabel="Estado del apto: ">
          {STATE_LABEL[apto.estado]}
        </StatusBadge>
        <p className="text-sm text-muted">{validityText(apto.vigencia, apto.estado)}</p>
      </div>
      {notices.map((text) => (
        <Alert key={text} tone="warning" role="status">
          {text}
        </Alert>
      ))}
      {doc ? (
        <div className="space-y-2">
          {date ? <p className="text-sm text-muted">Cargado el {date}.</p> : null}
          {doc.datosVisibles ? (
            <dl className="grid gap-x-4 gap-y-1 text-sm sm:grid-cols-[auto_1fr]">
              <dt className="font-semibold">Fecha de emisión</dt>
              <dd>{doc.fechaEmision ? formatDay(doc.fechaEmision) : 'No informada'}</dd>
              <dt className="font-semibold">Médico</dt>
              <dd className="break-words">{doc.medicoNombre ?? 'No informado'}</dd>
              <dt className="font-semibold">Matrícula</dt>
              <dd className="break-words">{doc.medicoMatricula ?? 'No informada'}</dd>
            </dl>
          ) : (
            <p className="text-sm">{OTHER_FAMILY_DATA}</p>
          )}
          {apto.estado === 'OBSERVADO' && doc.motivoObservacion ? (
            <Alert tone="warning">
              <span className="font-semibold">Motivo:</span> <span className="break-words">{doc.motivoObservacion}</span>
            </Alert>
          ) : null}
        </div>
      ) : (
        <p className="text-sm">Todavía no se cargó el apto de esta temporada.</p>
      )}
    </div>
  );
}
