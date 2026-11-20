import { beforeEach, describe, expect, it } from 'vitest';
import { listAllSeasons } from '@/features/admin-datos-deportivos/api';
import { ApiError } from '@/lib/api-error';
import type { FakeBackend } from '@/test/fakeBackend';
import { createBackend } from '@/test/render';
import { activateSeason, createSeason } from './api';
import { parseSeasonYear } from './season-form';

let backend: FakeBackend;
beforeEach(() => { backend = createBackend(); backend.signIn('marcos@example.com'); });
const posts = () => backend.requests.filter((r) => r.method === 'post' && r.url.startsWith('/admin/temporadas'));
const failure = async (promise: Promise<unknown>) => ApiError.from(await promise.then(() => null, (e: unknown) => e));

describe('season year entry', () => {
  it.each([
    ['2027', { ok: true, year: 2027 }],
    [' 2027 ', { ok: true, year: 2027 }],
    ['2020', { ok: true, year: 2020 }],
    ['2100', { ok: true, year: 2100 }],
    ['', { ok: false, reason: 'empty' }],
    ['   ', { ok: false, reason: 'empty' }],
    ['2019', { ok: false, reason: 'range' }],
    ['2101', { ok: false, reason: 'range' }],
    ['2027.5', { ok: false, reason: 'format' }],
    ['2027.0', { ok: false, reason: 'format' }],
    ['1e3', { ok: false, reason: 'format' }],
    ['dos mil', { ok: false, reason: 'format' }],
    ['-2027', { ok: false, reason: 'format' }],
    ['+2027', { ok: false, reason: 'format' }],
    ['02027', { ok: false, reason: 'format' }],
    ['9999999999999999999', { ok: false, reason: 'format' }],
  ])('%j -> %j', (text, expected) => {
    expect(parseSeasonYear(text)).toEqual(expected);
  });
});

describe('listing coherence', () => {
  it('reads every page and returns the seasons of one coherent snapshot', async () => {
    for (let year = 2000; year < 2150; year += 1) backend.addSeason(year, year === 2099);
    const seasons = await listAllSeasons();
    expect(seasons).toHaveLength(150);
    expect(backend.requests.filter((r) => r.url.startsWith('/admin/temporadas?'))).toHaveLength(2);
    expect(seasons.filter((s) => s.activa).map((s) => s.anio)).toEqual([2099]);
  });

  it('rejects a snapshot with two active seasons (pages that were read at different moments)', async () => {
    backend.addSeason(2026, true);
    backend.addSeason(2027, true);
    expect((await failure(listAllSeasons())).kind).toBe('server');
  });

  it('rejects pages whose total changes between reads', async () => {
    for (let year = 2000; year < 2150; year += 1) backend.addSeason(year);
    const all = backend.seasonsOnServer();
    backend.override('get', '/admin/temporadas?page=0&size=100', { status: 200, body: { contenido: all.slice(0, 100), pagina: 0, tamanio: 100, totalElementos: 150 } });
    backend.override('get', '/admin/temporadas?page=1&size=100', { status: 200, body: { contenido: all.slice(100), pagina: 1, tamanio: 100, totalElementos: 151 } });
    expect((await failure(listAllSeasons())).kind).toBe('server');
  });

  it('rejects a repeated season across pages', async () => {
    const a = backend.addSeason(2026);
    backend.override('get', '/admin/temporadas?page=0&size=100', { status: 200, body: { contenido: [{ ...a }, { ...a }], pagina: 0, tamanio: 100, totalElementos: 2 } });
    expect((await failure(listAllSeasons())).kind).toBe('server');
  });
});

describe('createSeason', () => {
  it('sends only the year with a fresh CSRF header and returns an inactive season', async () => {
    const created = await createSeason(2027);
    expect(posts()).toHaveLength(1);
    expect(posts()[0]).toMatchObject({ url: '/admin/temporadas', body: { anio: 2027 } });
    expect(Object.keys(posts()[0]!.body as object)).toEqual(['anio']);
    expect(posts()[0]!.csrfHeader).toBeTruthy();
    expect(created).toMatchObject({ anio: 2027, activa: false });
    expect(backend.seasonsOnServer()).toMatchObject([{ id: created.id, anio: 2027, activa: false }]);
  });

  it('maps a duplicated year to its own code and sends nothing else', async () => {
    backend.addSeason(2027);
    const error = await failure(createSeason(2027));
    expect(error.code).toBe('TEMPORADA_ANIO_DUPLICADO');
    expect(posts()).toHaveLength(1);
  });

  it('treats a 201 for another year, or already active, as an answer that cannot be confirmed', async () => {
    backend.override('post', '/admin/temporadas', { status: 201, body: { id: 'x', anio: 2030, activa: false } });
    expect((await failure(createSeason(2027))).kind).toBe('server');
    backend.override('post', '/admin/temporadas', { status: 201, body: { id: 'x', anio: 2027, activa: true } });
    expect((await failure(createSeason(2027))).kind).toBe('server');
    backend.override('post', '/admin/temporadas', { status: 201, body: { id: 'x', anio: '2027' } });
    expect((await failure(createSeason(2027))).kind).toBe('server');
  });

  it('never retries a lost response and does not add the season by itself', async () => {
    backend.override('post', '/admin/temporadas', 'network');
    expect((await failure(createSeason(2027))).kind).toBe('network');
    expect(posts()).toHaveLength(1);
    expect(backend.seasonsOnServer()).toEqual([]);
  });

  it('refuses to write when the identity changed after the CSRF token was read', async () => {
    const error = await failure(createSeason(2027, () => false));
    expect(error.code).toBe('IDENTIDAD_CAMBIADA');
    expect(posts()).toHaveLength(0);
  });
});

describe('activateSeason', () => {
  it('activates from zero with a null intent and sends exactly the mandatory body', async () => {
    const target = backend.addSeason(2027);
    const result = await activateSeason({ seasonId: target.id, expectedActiveId: null });
    expect(posts()[0]).toMatchObject({ url: `/admin/temporadas/${target.id}/activar`, body: { activaEsperadaId: null } });
    expect(Object.keys(posts()[0]!.body as object)).toEqual(['activaEsperadaId']);
    expect(posts()[0]!.csrfHeader).toBeTruthy();
    expect(result).toMatchObject({ id: target.id, activa: true });
  });

  it('replaces the active season when the intent names it, and leaves no other season active', async () => {
    const old = backend.addSeason(2026, true);
    const target = backend.addSeason(2027);
    await activateSeason({ seasonId: target.id, expectedActiveId: old.id });
    expect(backend.seasonsOnServer().filter((s) => s.activa).map((s) => s.id)).toEqual([target.id]);
  });

  it('is idempotent for the season that already is the active one, even with a stale intent', async () => {
    const target = backend.addSeason(2027, true);
    const result = await activateSeason({ seasonId: target.id, expectedActiveId: 'otra' });
    expect(result).toMatchObject({ id: target.id, activa: true });
  });

  it('surfaces EDICION_DESACTUALIZADA when another administrator activated a different season', async () => {
    const seen = backend.addSeason(2026, true);
    const other = backend.addSeason(2027);
    const target = backend.addSeason(2028);
    backend.activateSeasonBehindTheScenes(other.id);
    const error = await failure(activateSeason({ seasonId: target.id, expectedActiveId: seen.id }));
    expect(error.status).toBe(409);
    expect(error.code).toBe('EDICION_DESACTUALIZADA');
    expect(backend.seasonsOnServer().filter((s) => s.activa).map((s) => s.id)).toEqual([other.id]);
  });

  it('treats an answer for another season, or not active, as one that cannot be confirmed', async () => {
    const target = backend.addSeason(2027);
    backend.override('post', `/admin/temporadas/${target.id}/activar`, { status: 200, body: { id: 'otra', anio: 2027, activa: true } });
    expect((await failure(activateSeason({ seasonId: target.id, expectedActiveId: null }))).kind).toBe('server');
    backend.override('post', `/admin/temporadas/${target.id}/activar`, { status: 200, body: { id: target.id, anio: 2027, activa: false } });
    expect((await failure(activateSeason({ seasonId: target.id, expectedActiveId: null }))).kind).toBe('server');
  });

  it('never retries a lost response and refuses to write after an identity change', async () => {
    const target = backend.addSeason(2027);
    backend.override('post', `/admin/temporadas/${target.id}/activar`, 'network');
    expect((await failure(activateSeason({ seasonId: target.id, expectedActiveId: null }))).kind).toBe('network');
    expect(posts()).toHaveLength(1);
    const error = await failure(activateSeason({ seasonId: target.id, expectedActiveId: null }, () => false));
    expect(error.code).toBe('IDENTIDAD_CAMBIADA');
    expect(posts()).toHaveLength(1);
  });

  it('rejects an id that could alter the path without sending anything', async () => {
    for (const id of ['', ' ', '../x', 'a/b', 'a?b']) {
      expect((await failure(activateSeason({ seasonId: id, expectedActiveId: null }))).kind).toBe('validation');
    }
    expect(posts()).toHaveLength(0);
  });
});
