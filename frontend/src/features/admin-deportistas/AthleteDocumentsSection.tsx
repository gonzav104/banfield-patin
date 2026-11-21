import { ApiError } from '@/lib/api-error';
import { DocumentReviewDialog } from './DocumentReviewDialog';
import { AthleteDocumentsView } from './AthleteDocumentsView';
import { useAthleteDocuments } from './hooks';
import { documentsListErrorMessage } from './messages';
import { useDocumentReview } from './use-document-review';
import { useDocumentViewer } from './use-document-viewer';

/**
 * «Documentos» in the ADMIN athlete detail (U16): the metadata comes from the query cache; a file is fetched only when the
 * administrator asks to open it, and lives only inside the viewer (see `useDocumentViewer`).
 */
export function AthleteDocumentsSection({ athleteId }: { athleteId: string }) {
  const documents = useAthleteDocuments(athleteId);
  const viewer = useDocumentViewer(athleteId);
  const review = useDocumentReview(athleteId);
  const listError = documents.isError ? documentsListErrorMessage(ApiError.from(documents.error)) : null;
  return (
    <>
      <AthleteDocumentsView
        documents={documents.data}
        loading={documents.isPending}
        listError={listError}
        onRetryList={() => void documents.refetch()}
        reviewDisabled={review.pending || documents.isFetching || documents.isRefetchError}
        reviewFeedback={review.feedback}
        onReview={review.open}
        viewer={viewer.state}
        onOpen={viewer.open}
        onClose={viewer.close}
        onDownload={viewer.downloadPdf}
      />
      {review.selection ? (
        <DocumentReviewDialog document={review.selection.document} action={review.selection.action}
          pending={review.pending} onCancel={review.close} onConfirm={review.confirm} />
      ) : null}
    </>
  );
}
