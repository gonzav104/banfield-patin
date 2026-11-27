import { describe, expect, it } from 'vitest';
import { ApiError } from '@/lib/api-error';
import { aptoDataSignature, aptoFormSchema, schoolToday } from './form';
import { NOTICE_TEXT, formatDay, validityText } from './messages';
import { aptoDocumentSchema, aptoSchema, familySeasonsSchema } from './schemas';
import { aptoUploadFailure } from './upload-outcome';

const doc = {
  id: '00000000-0000-4000-a000-000000000001',
  estado: 'OBSERVADO' as const,
  fechaEmision: '2026-03-01',
  medicoNombre: 'Medico Sintetico',
  medicoMatricula: 'MP 0001',
  datosVisibles: true,
  motivoObservacion: 'Motivo sintetico',
  cargadoEn: '2026-03-10T14:00:00Z',
  revision: 3,
};

describe('apto schemas: what may reach a component', () => {
  it('keeps the data and the reason of a version that is visible', () => {
    const parsed = aptoDocumentSchema.parse(doc);
    expect(parsed).toMatchObject({ medicoNombre: 'Medico Sintetico', medicoMatricula: 'MP 0001', fechaEmision: '2026-03-01', motivoObservacion: 'Motivo sintetico', revision: 3 });
  });

  it('drops the data AND the reason when datosVisibles is false, even if the server sent them', () => {
    const parsed = aptoDocumentSchema.parse({ ...doc, datosVisibles: false });
    expect(parsed).toEqual({ id: doc.id, estado: 'OBSERVADO', datosVisibles: false, fechaEmision: null, medicoNombre: null, medicoMatricula: null, motivoObservacion: null, cargadoEn: doc.cargadoEn, revision: 3 });
    expect(JSON.stringify(parsed)).not.toMatch(/Medico|MP 0001|2026-03-01|sintetico/);
  });

  it('shows the reason only for an observed document and drops fields it does not know', () => {
    expect(aptoDocumentSchema.parse({ ...doc, estado: 'CARGADO' }).motivoObservacion).toBeNull();
    const parsed = aptoDocumentSchema.parse({ ...doc, storageKey: 'secreto', sha256: 'abc', nombreOriginal: 'DNI Ana.pdf' });
    expect(Object.keys(parsed)).not.toContain('storageKey');
    expect(JSON.stringify(parsed)).not.toMatch(/secreto|abc|DNI Ana/);
  });

  it('accepts a missing document and missing optional fields (the family has no revision)', () => {
    const apto = aptoSchema.parse({
      deportistaId: 'a1', temporadaId: 's1', anio: 2026, estado: 'FALTA', bloqueante: false,
      vigencia: { desde: '2026-01-01', hasta: '2026-12-31', confirmada: true, fuente: 'x' }, avisos: [],
    });
    expect(apto.documento ?? null).toBeNull();
    expect(aptoDocumentSchema.parse({ id: doc.id, estado: 'CARGADO', datosVisibles: false, cargadoEn: doc.cargadoEn }).revision).toBeNull();
  });

  it('rejects an unknown state, a malformed id and a malformed date', () => {
    expect(aptoSchema.safeParse({ deportistaId: 'a1', temporadaId: 's1', anio: 2026, estado: 'CASI', bloqueante: false, vigencia: { desde: '2026-01-01', hasta: '2026-12-31', confirmada: true }, avisos: [] }).success).toBe(false);
    expect(aptoDocumentSchema.safeParse({ ...doc, id: '../x' }).success).toBe(false);
    expect(aptoDocumentSchema.safeParse({ ...doc, fechaEmision: '01/03/2026' }).success).toBe(false);
  });

  it('keeps only id, year and active flag of a season: the sports data the server may add are dropped', () => {
    const rows = familySeasonsSchema.parse([{ temporadaId: 's1', anio: 2026, temporadaActiva: true, categoria: 'Sub-11', numeroCorredor: '12', estadoDeportivo: 'ACTIVO' }]);
    expect(rows).toEqual([{ temporadaId: 's1', anio: 2026, temporadaActiva: true }]);
  });
});

describe('apto form validation (the backend rules)', () => {
  const schema = aptoFormSchema('2026-06-15');
  const ok = { fechaEmision: '2026-06-01', medicoNombre: 'Medico Sintetico', medicoMatricula: 'MP 12.345' };
  const messages = (input: object) => {
    const result = schema.safeParse(input);
    return result.success ? [] : result.error.issues.map((i) => i.message);
  };

  it('accepts valid data and trims it', () => {
    expect(schema.parse({ fechaEmision: ' 2026-06-15 ', medicoNombre: '  Medico  ', medicoMatricula: ' M.N. 123/4 ' })).toEqual({ fechaEmision: '2026-06-15', medicoNombre: 'Medico', medicoMatricula: 'M.N. 123/4' });
  });

  it.each([
    ['', 'Ingresá la fecha de emisión.'],
    ['no-es-fecha', 'La fecha de emisión no es válida.'],
    ['2026-02-30', 'La fecha de emisión no es válida.'],
    ['1999-12-31', 'La fecha de emisión no es válida.'],
    ['2026-06-16', 'La fecha de emisión no puede ser futura.'],
  ])('rejects the issue date «%s»', (fecha, message) => {
    expect(messages({ ...ok, fechaEmision: fecha })).toContain(message);
  });

  it('rejects an empty, too long or control-character doctor name', () => {
    expect(messages({ ...ok, medicoNombre: '   ' })).toContain('Ingresá el nombre del médico.');
    expect(messages({ ...ok, medicoNombre: 'M'.repeat(151) })).toContain('El nombre puede tener hasta 150 caracteres.');
    expect(messages({ ...ok, medicoNombre: 'Medico\nSintetico' })).toContain('El nombre del médico tiene caracteres no válidos.');
    expect(messages({ ...ok, medicoNombre: 'M'.repeat(150) })).toEqual([]);
  });

  it.each(['***', '-1', 'MP<script>', 'MP\n1'])('rejects the license «%s»', (matricula) => {
    expect(messages({ ...ok, medicoMatricula: matricula })).toContain('La matrícula solo admite letras, números, espacios, puntos, guiones y barras.');
  });

  it('bounds the license length and accepts separators', () => {
    expect(messages({ ...ok, medicoMatricula: '9'.repeat(51) })).toContain('La matrícula puede tener hasta 50 caracteres.');
    expect(messages({ ...ok, medicoMatricula: '' })).toContain('Ingresá la matrícula.');
    for (const m of ['MP 12.345', 'M.N. 123/4', 'ABC-99', '1234', 'mp0001']) expect(messages({ ...ok, medicoMatricula: m })).toEqual([]);
  });

  it('never echoes the typed value in a message', () => {
    const typed = { fechaEmision: 'ZZZ-valor', medicoNombre: ' ', medicoMatricula: '***SECRETA***' };
    expect(messages(typed).join(' ')).not.toMatch(/ZZZ-valor|SECRETA/);
  });

  it('takes «today» in the school time zone, not in UTC', () => {
    // 02:00 UTC of the 15th is still the 14th in Buenos Aires.
    expect(schoolToday(new Date('2026-06-15T02:00:00Z'))).toBe('2026-06-14');
    expect(schoolToday(new Date('2026-06-15T03:00:00Z'))).toBe('2026-06-15');
  });

  it('gives equal data the same signature and any change a different one', () => {
    const a = aptoDataSignature(ok);
    expect(aptoDataSignature({ ...ok })).toBe(a);
    for (const change of [{ fechaEmision: '2026-06-02' }, { medicoNombre: 'Otro' }, { medicoMatricula: 'MP 2' }]) expect(aptoDataSignature({ ...ok, ...change })).not.toBe(a);
  });
});

describe('apto messages', () => {
  it('shows the provisional rule as provisional and never as confirmed', () => {
    expect(validityText({ hasta: '2027-12-31', confirmada: false }, 'EN_REVISION')).toBe('Vigente hasta el 31 de diciembre de 2027 (regla provisoria).');
    expect(validityText({ hasta: '2026-12-31', confirmada: true }, 'CUMPLIDO')).toBe('Vigente hasta el 31 de diciembre de 2026.');
    expect(validityText({ hasta: '2026-12-31', confirmada: true }, 'VENCIDO')).toBe('Venció el 31 de diciembre de 2026.');
    expect(NOTICE_TEXT.REQUISITOS_DE_LA_TEMPORADA_PENDIENTES_DE_CONFIRMACION).toMatch(/todavía no están confirmados/);
  });

  it('formats a calendar day without time-zone shifts', () => {
    expect(formatDay('2026-03-01')).toBe('1 de marzo de 2026');
  });
});

describe('apto upload failures', () => {
  const api = (status: number, code: string, detalles: object[] = []) => ApiError.from({ isAxiosError: true, response: { status, data: { codigo: code, detalles } }, config: {} });
  it('asks to fix the data on a 400 VALIDACION and names the fields, never values', () => {
    const failure = aptoUploadFailure(api(400, 'VALIDACION', [{ campo: 'medicoMatricula', mensaje: 'valor-secreto' }, { campo: 'otra', mensaje: 'x' }]), 5_000_000, false);
    expect(failure).toEqual({ action: 'fix-data', message: 'Revisá la matrícula.' });
    expect(aptoUploadFailure(api(400, 'VALIDACION'), null, false).message).toBe('Revisá los datos del apto.');
  });

  it('treats a conflict on a first upload as «refresh» and delegates the rest to the shared mapping', () => {
    expect(aptoUploadFailure(api(409, 'OPERACION_NO_REPETIBLE'), null, false).action).toBe('refresh');
    expect(aptoUploadFailure(api(409, 'OPERACION_EN_CURSO'), null, false).action).toBe('retry-same');
    expect(aptoUploadFailure(api(409, 'OPERACION_NO_REPETIBLE'), null, true).action).toBe('refresh');
    expect(aptoUploadFailure(api(413, 'ARCHIVO_DEMASIADO_GRANDE'), 5_000_000, false).action).toBe('choose-other-file');
    expect(aptoUploadFailure(new Error('network'), null, false).action).toBe('retry-same');
  });
});
