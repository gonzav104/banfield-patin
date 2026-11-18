import { beforeEach, describe, expect, it } from 'vitest';
import { ApiError } from '@/lib/api-error';
import type { FakeBackend } from '@/test/fakeBackend';
import { createBackend } from '@/test/render';
import { getAthleteLinks, getFamilyAthletes } from './api';
import { rawLink, seedMixedLinks } from './links-fixtures';

// MOCK backend (src/test/fakeBackend.ts mirrors GET /api/admin/deportistas/{id}/vinculos and GET /api/admin/familias/{id}/deportistas).
let backend: FakeBackend;

beforeEach(() => {
  backend = createBackend();
  backend.signIn('marcos@example.com');
});

const MISMATCH = 'El servidor respondió con datos que no corresponden.';
const FORMAT = 'El servidor respondió con un formato inesperado.';
const reads = () => backend.requests.filter((r) => !r.url.startsWith('/auth/'));

async function failure(promise: Promise<unknown>): Promise<ApiError> {
  try {
    await promise;
  } catch (error) {
    return ApiError.from(error);
  }
  throw new Error('It should have failed');
}

describe('getAthleteLinks', () => {
  it('reads the links of the athlete with ONE read request and keeps the revision inside the model', async () => {
    const { athlete, gomez } = seedMixedLinks(backend);
    const result = await getAthleteLinks(athlete.id);

    expect(reads().map((r) => `${r.method} ${r.url}`)).toEqual([`get /admin/deportistas/${athlete.id}/vinculos`]);
    expect(result.deportistaId).toBe(athlete.id);
    expect(result.versionVinculos).toBe(String(athlete.version));
    expect(result.vinculos).toHaveLength(3);
    expect(result.vinculos.find((l) => l.familiaId === gomez.id)).toMatchObject({ estado: 'ACTIVO', esPrincipal: true, familiaNombre: 'Familia Gómez' });
  });

  it('keeps no personal field of the athlete beyond the name', async () => {
    const { athlete } = seedMixedLinks(backend);
    const result = await getAthleteLinks(athlete.id);
    const text = JSON.stringify(result);
    for (const secret of ['30111322', '20301113227', 'Calle Falsa', 'autorizadoEn']) expect(text).not.toContain(secret);
  });

  it('encodes the id in the path', async () => {
    backend.override('get', '/admin/deportistas/a%2Fb/vinculos', { status: 404, body: { codigo: 'DEPORTISTA_NO_ENCONTRADO', mensaje: 'x' } });
    const error = await failure(getAthleteLinks('a/b'));
    expect(error.status).toBe(404);
    expect(reads()[0]!.url).toBe('/admin/deportistas/a%2Fb/vinculos');
  });

  it('encodes the family id in the path as well', async () => {
    backend.override('get', '/admin/familias/a%2Fb%3Fc/deportistas', { status: 404, body: { codigo: 'FAMILIA_NO_ENCONTRADA', mensaje: 'x' } });
    const error = await failure(getFamilyAthletes('a/b?c'));
    expect(error.status).toBe(404);
    expect(reads()[0]!.url).toBe('/admin/familias/a%2Fb%3Fc/deportistas');
  });

  it('answers an athlete with zero links with an empty list', async () => {
    const athlete = backend.addAdminAthlete({ nombre: 'Eva', apellido: 'Gómez', dni: '30222333' });
    expect((await getAthleteLinks(athlete.id)).vinculos).toEqual([]);
  });

  it('a 404 keeps its uniform code and a 403 its kind', async () => {
    expect(await failure(getAthleteLinks('dep-inexistente'))).toMatchObject({ status: 404, code: 'DEPORTISTA_NO_ENCONTRADO' });
    backend.override('get', '/admin/deportistas/dep-1/vinculos', { status: 403, body: { codigo: 'ACCESO_DENEGADO', mensaje: 'x' } });
    expect((await failure(getAthleteLinks('dep-1'))).kind).toBe('forbidden');
  });

  it('rejects an answer about ANOTHER athlete', async () => {
    backend.override('get', '/admin/deportistas/dep-1/vinculos', { status: 200, body: { deportistaId: 'dep-2', versionVinculos: '3', vinculos: [] } });
    expect(await failure(getAthleteLinks('dep-1'))).toMatchObject({ kind: 'server', message: MISMATCH });
  });

  it('rejects a row of ANOTHER athlete', async () => {
    backend.override('get', '/admin/deportistas/dep-1/vinculos', {
      status: 200,
      body: { deportistaId: 'dep-1', versionVinculos: '3', vinculos: [rawLink({ deportistaId: 'dep-1' }), rawLink({ deportistaId: 'dep-2', vinculoId: 'vin-y' })] },
    });
    expect(await failure(getAthleteLinks('dep-1'))).toMatchObject({ kind: 'server', message: MISMATCH });
  });

  it('rejects a row with a DIFFERENT revision than the header (they must be one snapshot)', async () => {
    backend.override('get', '/admin/deportistas/dep-1/vinculos', {
      status: 200,
      body: { deportistaId: 'dep-1', versionVinculos: '3', vinculos: [rawLink({ deportistaId: 'dep-1', versionVinculos: '4' })] },
    });
    expect(await failure(getAthleteLinks('dep-1'))).toMatchObject({ kind: 'server', message: MISMATCH });
  });

  it.each([
    ['an unknown state', { deportistaId: 'dep-1', versionVinculos: '3', vinculos: [rawLink({ deportistaId: 'dep-1', versionVinculos: '3', estado: 'OTRO' })] }],
    ['a malformed revision', { deportistaId: 'dep-1', versionVinculos: '3.5', vinculos: [] }],
    ['a revision too long', { deportistaId: 'dep-1', versionVinculos: '12345678901234567890', vinculos: [] }],
    ['a missing field', { deportistaId: 'dep-1', versionVinculos: '3', vinculos: [{ ...rawLink({ deportistaId: 'dep-1', versionVinculos: '3' }), familiaNombre: undefined }] }],
    ['no body shape', 'x'],
  ])('rejects %s with the fixed format error', async (_name, body) => {
    backend.override('get', '/admin/deportistas/dep-1/vinculos', { status: 200, body });
    expect(await failure(getAthleteLinks('dep-1'))).toMatchObject({ kind: 'server', message: FORMAT });
  });
});

describe('getFamilyAthletes', () => {
  it('reads the links of the family with ONE read request, ordered by the server', async () => {
    const { athlete, gomez } = seedMixedLinks(backend);
    const eva = backend.addAdminAthlete({ nombre: 'Eva', apellido: 'Abad', dni: '30222333' });
    backend.addAdminLink(gomez.id, eva.id, { estado: 'ACTIVO' });
    const rows = await getFamilyAthletes(gomez.id);

    expect(reads().map((r) => `${r.method} ${r.url}`)).toEqual([`get /admin/familias/${gomez.id}/deportistas`]);
    expect(rows.map((r) => `${r.deportistaApellido} ${r.deportistaNombre}`)).toEqual(['Abad Eva', 'Sintética Lucía']);
    expect(rows.every((r) => r.familiaId === gomez.id)).toBe(true);
    expect(rows.find((r) => r.deportistaId === athlete.id)).toMatchObject({ estado: 'ACTIVO', esPrincipal: true });
  });

  it('keeps no personal field beyond the names', async () => {
    const { gomez } = seedMixedLinks(backend);
    const text = JSON.stringify(await getFamilyAthletes(gomez.id));
    for (const secret of ['30111322', '20301113227', 'Calle Falsa', 'autorizadoEn']) expect(text).not.toContain(secret);
  });

  it('answers a family with no athletes with an empty list', async () => {
    const family = backend.addFamily('Familia Vacía');
    expect(await getFamilyAthletes(family.id)).toEqual([]);
  });

  it('a 404 keeps its uniform code', async () => {
    expect(await failure(getFamilyAthletes('fam-inexistente'))).toMatchObject({ status: 404, code: 'FAMILIA_NO_ENCONTRADA' });
  });

  it('rejects a row of ANOTHER family', async () => {
    backend.override('get', '/admin/familias/fam-1/deportistas', { status: 200, body: [rawLink({ familiaId: 'fam-1' }), rawLink({ familiaId: 'fam-2', vinculoId: 'vin-y' })] });
    expect(await failure(getFamilyAthletes('fam-1'))).toMatchObject({ kind: 'server', message: MISMATCH });
  });

  it.each([
    ['an unknown state', [rawLink({ familiaId: 'fam-1', estado: 'OTRO' })]],
    ['a malformed revision', [rawLink({ familiaId: 'fam-1', versionVinculos: 'x' })]],
    ['an object instead of a list', { vinculos: [] }],
  ])('rejects %s with the fixed format error', async (_name, body) => {
    backend.override('get', '/admin/familias/fam-1/deportistas', { status: 200, body });
    expect(await failure(getFamilyAthletes('fam-1'))).toMatchObject({ kind: 'server', message: FORMAT });
  });
});
