import { describe, expect, it } from 'vitest';
import { athleteHeaderSchema, seasonPageSchema, sportsHistorySchema, sportsRowSchema } from './schemas';

const row = {
  id: 'dt-1',
  deportistaId: 'dep-1',
  temporadaId: 'temp-1',
  anio: 2027,
  categoria: 'Sub 14',
  rama: 'Femenino',
  divisional: 'A',
  numeroCorredor: '007',
  estadoDeportivo: 'ACTIVO',
  version: '3',
};

describe('sports row contract (DeportistaTemporadaRespuesta)', () => {
  it('accepts a complete row and keeps its revision as the decimal string it is', () => {
    expect(sportsRowSchema.parse(row)).toEqual(row);
  });

  it('accepts every optional text as null', () => {
    const parsed = sportsRowSchema.parse({ ...row, categoria: null, rama: null, divisional: null, numeroCorredor: null });
    expect(parsed).toMatchObject({ categoria: null, rama: null, divisional: null, numeroCorredor: null });
  });

  it.each(['0', '7', '9223372036854775807', '0003'])('accepts the revision %j', (version) => {
    expect(sportsRowSchema.safeParse({ ...row, version }).success).toBe(true);
  });

  it.each([3, 3.5, '', '-1', '+1', '1.0', '1e3', ' 3', '3 ', 'abc', '12345678901234567890', null, undefined, ['3']])('rejects the revision %j: it must be a decimal string', (version) => {
    expect(sportsRowSchema.safeParse({ ...row, version }).success).toBe(false);
  });

  it.each([2019, 2101, 2027.5, '2027', null, undefined])('rejects the year %j', (anio) => {
    expect(sportsRowSchema.safeParse({ ...row, anio }).success).toBe(false);
  });

  it.each(['ACTIVA', 'activo', 'BAJA', '', null, undefined])('rejects the sports status %j', (estadoDeportivo) => {
    expect(sportsRowSchema.safeParse({ ...row, estadoDeportivo }).success).toBe(false);
  });

  it.each([
    ['categoria', 81],
    ['rama', 41],
    ['divisional', 81],
    ['numeroCorredor', 31],
  ])('rejects %s longer than its column', (field, length) => {
    expect(sportsRowSchema.safeParse({ ...row, [field]: 'x'.repeat(length) }).success).toBe(false);
  });

  it('drops any property that is not part of the contract', () => {
    const parsed = sportsRowSchema.parse({ ...row, dni: '30111322', escuelaId: 'e-1' });
    expect(parsed).not.toHaveProperty('dni');
    expect(parsed).not.toHaveProperty('escuelaId');
  });

  it('parses a history as an array of rows and rejects one bad row', () => {
    expect(sportsHistorySchema.parse([row, { ...row, id: 'dt-2', anio: 2026 }])).toHaveLength(2);
    expect(sportsHistorySchema.safeParse([row, { ...row, version: 1 }]).success).toBe(false);
    expect(sportsHistorySchema.safeParse({ contenido: [row] }).success).toBe(false);
  });
});

describe('athlete header contract', () => {
  it('reads ONLY the name and the state in the school, never the DNI, the CUIL or the contact data', () => {
    const parsed = athleteHeaderSchema.parse({
      id: 'dep-1',
      nombre: 'Lucía',
      apellido: 'Sintética',
      activo: false,
      dni: '30111322',
      cuil: '20301113227',
      domicilio: 'Calle Falsa 123',
      telefonoContacto: '11 5555-0000',
      version: '2',
    });
    expect(parsed).toEqual({ id: 'dep-1', nombre: 'Lucía', apellido: 'Sintética', activo: false });
  });

  it('rejects a header without the state in the school', () => {
    expect(athleteHeaderSchema.safeParse({ id: 'dep-1', nombre: 'A', apellido: 'B' }).success).toBe(false);
  });
});

describe('season page contract (Pagina<TemporadaRespuesta>)', () => {
  it('reads the total, which is what decides whether the school has seasons', () => {
    const page = seasonPageSchema.parse({ contenido: [{ id: 't-1', anio: 2027, activa: true }], pagina: 0, tamanio: 1, totalElementos: 81 });
    expect(page.totalElementos).toBe(81);
    expect(page.contenido).toHaveLength(1);
  });

  it('rejects a page without a total', () => {
    expect(seasonPageSchema.safeParse({ contenido: [], pagina: 0, tamanio: 1 }).success).toBe(false);
  });
});
