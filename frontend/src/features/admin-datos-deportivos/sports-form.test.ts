import { describe, expect, it } from 'vitest';
import { availableSeasons, EMPTY_VALUES, MAX_LENGTH, normalizeCode, normalizeText, toInput, validate, type Values } from './sports-form';

const values = (over: Partial<Values> = {}): Values => ({ ...EMPTY_VALUES, temporadaId: 'temp-1', ...over });

describe('normalisation (compatible with the backend DTO)', () => {
  it('trims and collapses the inner spaces of category, branch and division; blank becomes null', () => {
    expect(normalizeText('  Sub   14 ')).toBe('Sub 14');
    expect(normalizeText('Femenino \t A')).toBe('Femenino A');
    expect(normalizeText('   ')).toBeNull();
    expect(normalizeText('')).toBeNull();
  });

  it('only trims the runner number: leading zeros and inner spaces are kept, blank becomes null', () => {
    expect(normalizeCode(' 007 ')).toBe('007');
    expect(normalizeCode('0008')).toBe('0008');
    expect(normalizeCode('12  34')).toBe('12  34');
    expect(normalizeCode('  ')).toBeNull();
  });

  it('builds the exact payload: a null for every blank optional, the text as typed after normalising and the chosen status', () => {
    const input = toInput(values({ categoria: '  Sub   14 ', rama: '', divisional: ' A ', numeroCorredor: ' 007 ', estadoDeportivo: 'SUSPENDIDO' }));
    expect(input).toEqual({ temporadaId: 'temp-1', categoria: 'Sub 14', rama: null, divisional: 'A', numeroCorredor: '007', estadoDeportivo: 'SUSPENDIDO' });
    expect(Object.keys(input).sort()).toEqual(['categoria', 'divisional', 'estadoDeportivo', 'numeroCorredor', 'rama', 'temporadaId']);
  });

  it('starts as ACTIVO with everything else empty', () => {
    expect(EMPTY_VALUES).toEqual({ temporadaId: '', categoria: '', rama: '', divisional: '', numeroCorredor: '', estadoDeportivo: 'ACTIVO' });
  });
});

describe('validation (the rules of the backend, no catalogue and nothing mandatory but the season)', () => {
  it('accepts a season alone: category, branch, division and number are optional', () => {
    expect(validate(values())).toEqual({});
  });

  it('asks for the season', () => {
    expect(validate(values({ temporadaId: '' }))).toEqual({ temporadaId: 'Elegí una temporada.' });
  });

  it.each([
    ['categoria', 80, 'La categoría puede tener hasta 80 caracteres.'],
    ['rama', 40, 'La rama puede tener hasta 40 caracteres.'],
    ['divisional', 80, 'La divisional puede tener hasta 80 caracteres.'],
    ['numeroCorredor', 30, 'El número de corredor puede tener hasta 30 caracteres.'],
  ] as const)('%s: %i characters pass and one more does not', (field, max, message) => {
    expect(MAX_LENGTH[field]).toBe(max);
    expect(validate(values({ [field]: 'x'.repeat(max) }))).toEqual({});
    expect(validate(values({ [field]: 'x'.repeat(max + 1) }))).toEqual({ [field]: message });
  });

  it('measures the NORMALISED value: the spaces that would be trimmed or collapsed do not count', () => {
    expect(validate(values({ categoria: `   ${'x'.repeat(80)}   ` }))).toEqual({});
    expect(validate(values({ categoria: `${'x'.repeat(39)}     ${'y'.repeat(40)}` }))).toEqual({});
    expect(validate(values({ numeroCorredor: `  ${'7'.repeat(30)}  ` }))).toEqual({});
  });

  it.each(['\u0000', '\u0007', '\u007f'])('rejects the control character %j in every field', (control) => {
    for (const field of ['categoria', 'rama', 'divisional', 'numeroCorredor'] as const) {
      expect(validate(values({ [field]: `a${control}b` }))[field], field).toBe('No puede contener caracteres de control.');
    }
  });

  it('a tab inside the texts is a space for the backend (collapsed), but not for the runner number', () => {
    expect(validate(values({ categoria: 'Sub\t14' }))).toEqual({});
    expect(validate(values({ numeroCorredor: '7\t8' }))).toEqual({ numeroCorredor: 'No puede contener caracteres de control.' });
  });

  it('reports every wrong field at once', () => {
    const errors = validate(values({ temporadaId: '', categoria: 'x'.repeat(81), rama: 'a\u0000' }));
    expect(Object.keys(errors).sort()).toEqual(['categoria', 'rama', 'temporadaId']);
  });
});

describe('the seasons that can still be loaded', () => {
  const seasons = [
    { id: 't26', anio: 2026, activa: false },
    { id: 't28', anio: 2028, activa: true },
    { id: 't27', anio: 2027, activa: false },
  ];

  it('keeps only the seasons the athlete has no row in, newest first, with the active one flagged', () => {
    const history = [{ temporadaId: 't27' }];
    expect(availableSeasons(seasons, history)).toEqual([
      { id: 't28', anio: 2028, activa: true },
      { id: 't26', anio: 2026, activa: false },
    ]);
  });

  it('offers inactive seasons too', () => {
    expect(availableSeasons(seasons, []).map((s) => s.id)).toEqual(['t28', 't27', 't26']);
  });

  it('is empty when every season is used or there is none', () => {
    expect(availableSeasons(seasons, [{ temporadaId: 't26' }, { temporadaId: 't27' }, { temporadaId: 't28' }])).toEqual([]);
    expect(availableSeasons([], [])).toEqual([]);
  });

  it('ignores a history row of a season the list does not carry', () => {
    expect(availableSeasons(seasons, [{ temporadaId: 'otra' }]).map((s) => s.id)).toEqual(['t28', 't27', 't26']);
  });
});
