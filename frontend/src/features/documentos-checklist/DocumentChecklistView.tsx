import { Alert } from '@/components/ui/alert';
import { Button } from '@/components/ui/button';
import { StatusBadge } from '@/features/familia/StatusBadge';
import { LoadingBlock } from '@/features/familia/FamilyStates';
import { CHECKLIST_LOADING, CHECKLIST_PARTIAL_NOTE, CHECKLIST_TITLE, requirementLabel, STATE_LABEL, STATE_TONE } from './messages';
import type { DocumentChecklist } from './schemas';

export interface DocumentChecklistViewProps {
  checklist: DocumentChecklist | undefined;
  loading: boolean;
  error: string | null;
  onRetry: () => void;
  /** Where the existing document actions are (an element id on the same screen). Offered only when something can be done. */
  actionsId?: string;
  actionsLabel?: string;
}

function goTo(id: string) {
  const target = globalThis.document.getElementById(id);
  target?.scrollIntoView?.({ block: 'start' });
  target?.focus();
}

/**
 * Presentational, shared by the family sheet and the administrative sheet: the PARTIAL checklist of the athlete's DNI. Each
 * requirement is a state in text (never only colour); an observed one keeps its reason. It says it is partial, and it never
 * shows an overall percentage or a registration state.
 */
export function DocumentChecklistView({ checklist, loading, error, onRetry, actionsId, actionsLabel = 'Ir a los documentos' }: DocumentChecklistViewProps) {
  const actionable = checklist?.requisitos.some((r) => r.estado === 'FALTA' || r.estado === 'OBSERVADO') ?? false;
  return (
    <section aria-labelledby="document-checklist-title" aria-busy={loading} className="space-y-3 rounded-2xl border border-line bg-white p-5">
      <h2 id="document-checklist-title" className="text-lg font-bold">
        {CHECKLIST_TITLE}
      </h2>
      <p className="text-sm leading-relaxed text-muted">{CHECKLIST_PARTIAL_NOTE}</p>
      {error ? (
        <div className="space-y-3">
          <Alert tone="error">{error}</Alert>
          <Button variant="outline" onClick={onRetry}>
            Reintentar
          </Button>
        </div>
      ) : loading || !checklist ? (
        <LoadingBlock label={CHECKLIST_LOADING} />
      ) : (
        <>
          <ul className="divide-y divide-line rounded-xl border border-line">
            {checklist.requisitos.map((requirement) => (
              <li key={requirement.tipo} className="space-y-1 px-4 py-3">
                <div className="flex flex-wrap items-center justify-between gap-2">
                  <p className="min-w-0 break-words font-semibold">{requirementLabel(requirement.tipo)}</p>
                  <StatusBadge tone={STATE_TONE[requirement.estado]} srLabel="Estado: ">
                    {STATE_LABEL[requirement.estado]}
                  </StatusBadge>
                </div>
                {requirement.estado === 'OBSERVADO' && requirement.motivoObservacion?.trim() ? (
                  <p className="break-words text-sm">Motivo de la observación: {requirement.motivoObservacion.trim()}</p>
                ) : null}
              </li>
            ))}
          </ul>
          {actionsId && actionable ? (
            <Button variant="outline" onClick={() => goTo(actionsId)}>
              {actionsLabel}
            </Button>
          ) : null}
        </>
      )}
    </section>
  );
}
