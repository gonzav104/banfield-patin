import { describe, expect, it } from 'vitest';
import { dniSummary, requirementLabel } from './messages';
import { documentChecklistSchema, type DocumentChecklist, type RequirementState } from './schemas';

const checklist = (front: RequirementState, back: RequirementState): DocumentChecklist => ({
  deportistaId: 'dep-1',
  parcial: true,
  alcance: 'DNI_DEPORTISTA',
  requisitos: [
    { tipo: 'DNI_DEPORTISTA_FRENTE', estado: front },
    { tipo: 'DNI_DEPORTISTA_DORSO', estado: back },
  ],
});

describe('dniSummary: the most urgent state of the two DNI sides wins', () => {
  it.each<[RequirementState, RequirementState, string]>([
    ['FALTA', 'OBSERVADO', 'DNI del deportista: observado, hay que reemplazarlo'],
    ['OBSERVADO', 'FALTA', 'DNI del deportista: observado, hay que reemplazarlo'],
    ['CUMPLIDO', 'OBSERVADO', 'DNI del deportista: observado, hay que reemplazarlo'],
    ['EN_REVISION', 'FALTA', 'DNI del deportista: falta cargar'],
    ['FALTA', 'FALTA', 'DNI del deportista: falta cargar'],
    ['CUMPLIDO', 'FALTA', 'DNI del deportista: falta cargar'],
    ['EN_REVISION', 'CUMPLIDO', 'DNI del deportista: en revisión'],
    ['EN_REVISION', 'EN_REVISION', 'DNI del deportista: en revisión'],
    ['CUMPLIDO', 'CUMPLIDO', 'DNI del deportista: aprobado'],
  ])('%s + %s → «%s»', (front, back, text) => {
    expect(dniSummary(checklist(front, back)).text).toBe(text);
  });

  it('returns the state that decided, and says only what is evaluated (never a registration state or a percentage)', () => {
    expect(dniSummary(checklist('CUMPLIDO', 'FALTA')).state).toBe('FALTA');
    for (const front of ['CUMPLIDO', 'EN_REVISION', 'OBSERVADO', 'FALTA'] as const) {
      for (const back of ['CUMPLIDO', 'EN_REVISION', 'OBSERVADO', 'FALTA'] as const) {
        expect(dniSummary(checklist(front, back)).text).toMatch(/^DNI del deportista: /);
        expect(dniSummary(checklist(front, back)).text).not.toMatch(/%|listo|empadron/i);
      }
    }
  });
});

describe('the checklist contract', () => {
  it('names the two DNI sides and falls back to a generic name for a type it does not know', () => {
    expect(requirementLabel('DNI_DEPORTISTA_FRENTE')).toBe('DNI – frente');
    expect(requirementLabel('DNI_DEPORTISTA_DORSO')).toBe('DNI – dorso');
    expect(requirementLabel('OTRO_TIPO')).toBe('Documento');
  });

  it('accepts only the four states and only a partial checklist', () => {
    expect(documentChecklistSchema.safeParse(checklist('FALTA', 'FALTA')).success).toBe(true);
    expect(documentChecklistSchema.safeParse({ ...checklist('FALTA', 'FALTA'), parcial: false }).success).toBe(false);
    expect(documentChecklistSchema.safeParse(checklist('LISTO_PARA_PRESENTAR' as RequirementState, 'FALTA')).success).toBe(false);
  });
});
