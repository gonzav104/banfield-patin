import { describe, expect, it } from 'vitest';
import { athleteLinksSchema, familyAthletesSchema, linkRowSchema } from './schemas';
import { rawLink } from './links-fixtures';

describe('link row schema', () => {
  it('keeps only what the screens need and drops what the backend may add, without keeping any personal field', () => {
    const parsed = linkRowSchema.parse(rawLink({ dni: '30111322', cuil: '20301113227', autorizadoPor: 'x', domicilio: 'Calle Falsa 123' }));
    expect(Object.keys(parsed).sort()).toEqual(
      ['deportistaActivo', 'deportistaApellido', 'deportistaId', 'deportistaNombre', 'esPrincipal', 'estado', 'familiaId', 'familiaNombre', 'vinculoId', 'versionVinculos'].sort(),
    );
  });

  it.each(['PENDIENTE', 'ACTIVO', 'RECHAZADO', 'REVOCADO'])('accepts the state %s', (estado) => {
    expect(linkRowSchema.safeParse(rawLink({ estado })).success).toBe(true);
  });

  it.each(['activo', 'ACTIVA', 'INACTIVO', 'BLOQUEADO', '', null, 1, undefined])('rejects the unknown state %j (a malformed answer, never drawn)', (estado) => {
    expect(linkRowSchema.safeParse(rawLink({ estado })).success).toBe(false);
  });

  it.each(['0', '1', '42', '9223372036854775807', '1234567890123456789'])('accepts the decimal revision %j', (versionVinculos) => {
    expect(linkRowSchema.safeParse(rawLink({ versionVinculos })).success).toBe(true);
  });

  it.each(['', 'abc', '-1', '1.5', '1e3', ' 1', '1 ', '0x10', '12345678901234567890', '１２', 5, null, undefined])('rejects the malformed revision %j', (versionVinculos) => {
    expect(linkRowSchema.safeParse(rawLink({ versionVinculos })).success).toBe(false);
  });

  it.each(['vinculoId', 'familiaId', 'familiaNombre', 'deportistaId', 'deportistaNombre', 'deportistaApellido', 'deportistaActivo', 'estado', 'esPrincipal', 'versionVinculos'])(
    'rejects a row with no %s',
    (field) => {
      const row: Record<string, unknown> = rawLink();
      delete row[field];
      expect(linkRowSchema.safeParse(row).success).toBe(false);
    },
  );

  it('does not need autorizadoEn (it is never asked for nor drawn): rows without it are valid', () => {
    const row: Record<string, unknown> = rawLink();
    delete row.autorizadoEn;
    const parsed = linkRowSchema.safeParse(row);
    expect(parsed.success).toBe(true);
    expect(parsed.success && 'autorizadoEn' in parsed.data).toBe(false);
  });

  it.each(['esPrincipal', 'deportistaActivo'])('rejects a non boolean %s', (field) => {
    expect(linkRowSchema.safeParse(rawLink({ [field]: 'true' })).success).toBe(false);
    expect(linkRowSchema.safeParse(rawLink({ [field]: 1 })).success).toBe(false);
  });
});

describe('links of an athlete schema', () => {
  it('keeps the revision of the athlete links inside the model, as a decimal string', () => {
    const parsed = athleteLinksSchema.parse({ deportistaId: 'dep-x', versionVinculos: '3', vinculos: [rawLink()] });
    expect(parsed.versionVinculos).toBe('3');
    expect(parsed.vinculos).toHaveLength(1);
  });

  it('accepts zero links (the revision exists even then)', () => {
    expect(athleteLinksSchema.safeParse({ deportistaId: 'dep-x', versionVinculos: '0', vinculos: [] }).success).toBe(true);
  });

  it.each([
    ['no revision', { deportistaId: 'dep-x', vinculos: [] }],
    ['a malformed revision', { deportistaId: 'dep-x', versionVinculos: 'x', vinculos: [] }],
    ['a revision too long', { deportistaId: 'dep-x', versionVinculos: '12345678901234567890', vinculos: [] }],
    ['no athlete id', { versionVinculos: '0', vinculos: [] }],
    ['links that are not a list', { deportistaId: 'dep-x', versionVinculos: '0', vinculos: {} }],
    ['no links', { deportistaId: 'dep-x', versionVinculos: '0' }],
    ['a malformed link', { deportistaId: 'dep-x', versionVinculos: '0', vinculos: [rawLink({ estado: 'OTRO' })] }],
    ['null', null],
    ['a list', []],
  ])('rejects %s', (_name, body) => {
    expect(athleteLinksSchema.safeParse(body).success).toBe(false);
  });
});

describe('athletes of a family schema', () => {
  it('accepts an empty list and a list of rows', () => {
    expect(familyAthletesSchema.safeParse([]).success).toBe(true);
    expect(familyAthletesSchema.safeParse([rawLink(), rawLink({ vinculoId: 'vin-y' })]).success).toBe(true);
  });

  it.each([['an object', {}], ['null', null], ['a malformed row', [rawLink({ versionVinculos: 'x' })]], ['a row with an unknown state', [rawLink({ estado: 'X' })]]])(
    'rejects %s',
    (_name, body) => {
      expect(familyAthletesSchema.safeParse(body).success).toBe(false);
    },
  );
});
