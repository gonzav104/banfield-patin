import { dniSummary } from '@/features/documentos-checklist/messages';
import { useDocumentChecklist } from './hooks';
import type { FamilyIdentity } from './identity';

/**
 * One line about the athlete's DNI in a dashboard card (U20), from the same partial checklist the sheet shows (the same query key; the dashboard
 * and the sheet are different screens, so the sheet may ask again). Only for the few athletes of the dashboard preview. While it loads it shows nothing; if it
 * fails it says so without blocking the card, which is still a link to the sheet.
 */
export function DniSummaryLine({ identity, athleteId }: { identity: FamilyIdentity; athleteId: string }) {
  const checklist = useDocumentChecklist(identity, athleteId);
  if (checklist.isPending) return null;
  if (checklist.isError) return <p className="text-sm text-muted">Documentación no disponible por ahora.</p>;
  const summary = dniSummary(checklist.data);
  return <p className={summary.state === 'OBSERVADO' ? 'text-sm font-semibold text-amber-900' : 'text-sm text-muted'}>{summary.text}</p>;
}
