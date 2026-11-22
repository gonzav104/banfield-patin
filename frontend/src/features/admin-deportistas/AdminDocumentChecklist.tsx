import { DocumentChecklistView } from '@/features/documentos-checklist/DocumentChecklistView';
import { checklistErrorMessage } from '@/features/documentos-checklist/messages';
import type { RequirementState } from '@/features/documentos-checklist/schemas';
import { ApiError } from '@/lib/api-error';
import { ADMIN_DOCUMENTS_SECTION_ID } from './AthleteDocumentsView';
import { useAdminDocumentChecklist } from './hooks';

/** What the club can decide in «Documentos»: only a DNI side that is waiting for its review (a missing or observed one waits for the family). */
const ADMIN_ACTION_STATES: readonly RequirementState[] = ['EN_REVISION'];

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
      actionStates={ADMIN_ACTION_STATES}
    />
  );
}
