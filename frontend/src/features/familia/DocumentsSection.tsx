import { Button } from '@/components/ui/button';
import { DNI_TYPES, TUTOR_DNI_TYPES, type DniType, type DocumentOwnerKind } from './api';
import { DniSlot } from './DniSlot';
import { ErrorBlock, LoadingBlock } from './FamilyStates';
import { useAthleteDocuments, useUploadAvailability } from './hooks';
import type { FamilyIdentity } from './identity';
import type { AthleteDocument } from './schemas';
import { useDocumentUpload } from './use-document-upload';

const TITLES: Record<DniType, string> = {
  DNI_DEPORTISTA_FRENTE: 'DNI – frente',
  DNI_DEPORTISTA_DORSO: 'DNI – dorso',
  DNI_TUTOR_FRENTE: 'DNI – frente',
  DNI_TUTOR_DORSO: 'DNI – dorso',
};

export const UPLOAD_DISABLED_TEXT = 'La carga de documentos no está habilitada por el momento.';
export const AVAILABILITY_UNKNOWN_TEXT = 'No pudimos comprobar si la carga de documentos está disponible.';
export const PRIVACY_NOTE =
  'Antes de enviar una foto, la procesamos en tu dispositivo para quitarle datos ocultos, como la ubicación. Los PDF se envían tal como están.';

/**
 * A slot with uploads enabled: owns ONE upload attempt (key + bytes) for its DNI side. It stays mounted after a
 * successful upload, so the confirmation is still shown once the list reports the new document.
 */
function UploadableSlot(props: { identity: FamilyIdentity; athleteId: string; tipo: DniType; maxBytes: number; document: AthleteDocument | null; kind: DocumentOwnerKind }) {
  const { document, ...options } = props;
  // An OBSERVED document can be replaced (U18); any other state is read-only for the family.
  const upload = useDocumentUpload({ ...options, replaces: document?.estado === 'OBSERVADO' ? document.id : null });
  return <DniSlot title={TITLES[props.tipo]} document={document} upload={upload} unavailableText={null} />;
}

/**
 * "Documentos del deportista" in the athlete sheet: the two DNI sides with the state the server reports and, for an
 * empty side, the upload flow. Uploads are offered only when the backend says they are enabled; the backend still
 * authorizes and validates every upload. Other document types are not shown in this unit.
 */
export function DocumentsSection({ identity, athleteId, kind = 'deportista' }: { identity: FamilyIdentity; athleteId: string; kind?: DocumentOwnerKind }) {
  const tutor = kind === 'tutor';
  const types = tutor ? TUTOR_DNI_TYPES : DNI_TYPES;
  const documents = useAthleteDocuments(identity, athleteId, kind);
  const availability = useUploadAvailability(identity);

  const current = (tipo: DniType): AthleteDocument | null => documents.data?.find((d) => d.tipo === tipo) ?? null;
  const maxBytes = availability.data?.habilitada ? availability.data.maxBytes : null;
  const unavailableText = availability.isError ? AVAILABILITY_UNKNOWN_TEXT : availability.data && !maxBytes ? UPLOAD_DISABLED_TEXT : null;

  return (
    <section aria-labelledby="documents-title" className="space-y-4 rounded-2xl border border-line bg-white p-5">
      <div className="space-y-2">
        <h2 id="documents-title" className="text-lg font-bold">
          {tutor ? 'Documentos del tutor' : 'Documentos del deportista'}
        </h2>
        <p className="text-sm leading-relaxed text-muted">
          {tutor ? 'Subí una foto o un PDF del DNI del tutor. El club lo revisa antes de usarlo.' : 'Subí una foto o un PDF del DNI del deportista. El club lo revisa antes de usarlo.'}
        </p>
        {maxBytes ? <p className="text-sm leading-relaxed text-muted">{PRIVACY_NOTE}</p> : null}
      </div>

      {documents.isPending ? <LoadingBlock label="Cargando documentos…" /> : null}
      {documents.isError ? <ErrorBlock error={documents.error} onRetry={() => void documents.refetch()} /> : null}
      {documents.data ? (
        <div className="space-y-4">
          {types.map((tipo) => {
            const document = current(tipo);
            if (maxBytes) {
              return <UploadableSlot key={tipo} identity={identity} athleteId={athleteId} tipo={tipo} maxBytes={maxBytes} document={document} kind={kind} />;
            }
            return <DniSlot key={tipo} title={TITLES[tipo]} document={document} upload={null} unavailableText={unavailableText} />;
          })}
          {availability.isError ? (
            <Button variant="outline" onClick={() => void availability.refetch()}>
              Volver a comprobar
            </Button>
          ) : null}
        </div>
      ) : null}
    </section>
  );
}
