import type { BadgeTone } from '@/features/familia/StatusBadge';
import type { DocumentChecklist, RequirementState } from './schemas';

export const CHECKLIST_TITLE = 'Checklist de documentación';
export const CHECKLIST_LOADING = 'Cargando el checklist…';
/** The checklist is partial and says so: it is not whether the athlete can be registered with the Federation. */
export const CHECKLIST_PARTIAL_NOTE =
  'Este checklist es parcial: solo revisa el DNI del deportista (frente y dorso). No indica si el deportista está listo para empadronarse.';

const REQUIREMENT_LABEL: Record<string, string> = {
  DNI_DEPORTISTA_FRENTE: 'DNI – frente',
  DNI_DEPORTISTA_DORSO: 'DNI – dorso',
};
/** A requirement this screen does not know yet is still listed, with a generic name. */
export const requirementLabel = (tipo: string): string => (Object.hasOwn(REQUIREMENT_LABEL, tipo) ? REQUIREMENT_LABEL[tipo]! : 'Documento');

export const STATE_LABEL: Record<RequirementState, string> = {
  CUMPLIDO: 'Aprobado',
  EN_REVISION: 'En revisión',
  OBSERVADO: 'Observado',
  FALTA: 'Falta',
};
/** The same tones as the DNI slots: a missing side is neutral text, an observed one is the only warning. */
export const STATE_TONE: Record<RequirementState, BadgeTone> = {
  CUMPLIDO: 'positive',
  EN_REVISION: 'neutral',
  OBSERVADO: 'warning',
  FALTA: 'neutral',
};

export function checklistErrorMessage(kind: string): string {
  if (kind === 'forbidden') return 'Esta cuenta no tiene permiso para ver el checklist.';
  if (kind === 'unauthenticated') return 'La sesión venció. Es necesario ingresar de nuevo.';
  if (kind === 'network') return 'No se pudo conectar con el servidor.';
  return 'No se pudo cargar el checklist.';
}

/**
 * One line about the athlete's DNI for a summary (the family dashboard). The most urgent state wins: observed, then missing,
 * then under review, then approved. It says what is evaluated («DNI del deportista»): it is never a statement about the
 * registration as a whole and carries no percentage.
 */
export function dniSummary(checklist: DocumentChecklist): { text: string; state: RequirementState } {
  const states = checklist.requisitos.map((r) => r.estado);
  if (states.includes('OBSERVADO')) return { text: 'DNI del deportista: observado, hay que reemplazarlo', state: 'OBSERVADO' };
  if (states.includes('FALTA')) return { text: 'DNI del deportista: falta cargar', state: 'FALTA' };
  if (states.includes('EN_REVISION')) return { text: 'DNI del deportista: en revisión', state: 'EN_REVISION' };
  return { text: 'DNI del deportista: aprobado', state: 'CUMPLIDO' };
}
