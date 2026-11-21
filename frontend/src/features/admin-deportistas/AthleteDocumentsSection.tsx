import { ApiError } from '@/lib/api-error';
import { AthleteDocumentsView } from './AthleteDocumentsView';
import { useAthleteDocuments } from './hooks';
import { documentsListErrorMessage } from './messages';
import { useDocumentViewer } from './use-document-viewer';

/**
 * «Documentos» in the ADMIN athlete detail (U16): the metadata comes from the query cache; a file is fetched only when the
 * administrator asks to open it, and lives only inside the viewer (see `useDocumentViewer`).
 */
export function AthleteDocumentsSection({ athleteId }: { athleteId: string }) {
  const documents = useAthleteDocuments(athleteId);
  const viewer = useDocumentViewer(athleteId);
  const listError = documents.isError && !documents.data ? documentsListErrorMessage(ApiError.from(documents.error)) : null;
  return (
    <AthleteDocumentsView
      documents={documents.data}
      loading={documents.isPending}
      listError={listError}
      onRetryList={() => void documents.refetch()}
      viewer={viewer.state}
      onOpen={viewer.open}
      onClose={viewer.close}
      onDownload={viewer.downloadPdf}
    />
  );
}
