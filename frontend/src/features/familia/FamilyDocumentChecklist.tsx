import { DocumentChecklistView } from '@/features/documentos-checklist/DocumentChecklistView';
import { checklistErrorMessage } from '@/features/documentos-checklist/messages';
import { ApiError } from '@/lib/api-error';
import { DOCUMENTS_SECTION_ID } from './DocumentsSection';
import { useDocumentChecklist } from './hooks';
import type { FamilyIdentity } from './identity';

/** The partial checklist of the athlete's DNI in the family sheet (U20). It points to the DNI actions that are already below it. */
export function FamilyDocumentChecklist({ identity, athleteId }: { identity: FamilyIdentity; athleteId: string }) {
  const checklist = useDocumentChecklist(identity, athleteId);
  return (
    <DocumentChecklistView
      checklist={checklist.data}
      loading={checklist.isPending}
      error={checklist.isError ? checklistErrorMessage(ApiError.from(checklist.error).kind) : null}
      onRetry={() => void checklist.refetch()}
      actionsId={DOCUMENTS_SECTION_ID}
      actionsLabel="Ir a los documentos"
    />
  );
}
