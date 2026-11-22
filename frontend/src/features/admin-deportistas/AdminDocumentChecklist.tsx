import { DocumentChecklistView } from '@/features/documentos-checklist/DocumentChecklistView';
import { checklistErrorMessage } from '@/features/documentos-checklist/messages';
import { ApiError } from '@/lib/api-error';
import { ADMIN_DOCUMENTS_SECTION_ID } from './AthleteDocumentsView';
import { useAdminDocumentChecklist } from './hooks';

/**
 * The partial checklist of the athlete's DNI in the administrative ficha (U20): the SAME calculation the family sees, with the
 * review and the download still in «Documentos» just below. It does not list pending documents (that is the inbox).
 */
export function AdminDocumentChecklist({ athleteId }: { athleteId: string }) {
  const checklist = useAdminDocumentChecklist(athleteId);
  return (
    <DocumentChecklistView
      checklist={checklist.data}
      loading={checklist.isPending}
      error={checklist.isError ? checklistErrorMessage(ApiError.from(checklist.error).kind) : null}
      onRetry={() => void checklist.refetch()}
      actionsId={ADMIN_DOCUMENTS_SECTION_ID}
      actionsLabel="Ir a los documentos"
    />
  );
}
