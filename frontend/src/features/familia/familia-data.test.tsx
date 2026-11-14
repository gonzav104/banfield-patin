import { QueryClientProvider, type QueryClient } from '@tanstack/react-query';
import { renderHook, waitFor } from '@testing-library/react';
import type { ReactNode } from 'react';
import { beforeEach, describe, expect, it } from 'vitest';
import { createQueryClient } from '@/app/providers';
import { ApiError } from '@/lib/api-error';
import { http } from '@/lib/http';
import type { FakeBackend } from '@/test/fakeBackend';
import { createBackend, family } from '@/test/render';
import { fetchAthletes, fetchMyFamily, isFamilyUnavailable } from './api';
import { familyKeys, useAthletes, useMyFamily } from './hooks';
import { familyErrorMessage } from './messages';

// MOCK backend (see src/test/fakeBackend.ts). Real-backend evidence lives in e2e/portal.real.spec.ts.
let backend: FakeBackend;

beforeEach(() => {
  backend = createBackend();
  backend.signIn(family.email);
});

const portalRequests = () => backend.requests.filter((r) => r.url.startsWith('/familia/'));

function wrapperFor(client: QueryClient) {
  return function Wrapper({ children }: { children: ReactNode }) {
    return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
  };
}

describe('family portal API (mock backend)', () => {
  it('parses the family and drops the tutors contact details', async () => {
    backend.addPortalFamily('familia-1', 'Familia Gómez', [
      { id: 't1', nombre: 'Marta', apellido: 'Gómez', parentesco: 'Madre', telefono: '11-5555-0000', email: 'marta@example.com' },
    ]);

    const result = await fetchMyFamily();

    expect(result).toEqual({
      id: 'familia-1',
      nombreReferencia: 'Familia Gómez',
      tutores: [{ id: 't1', nombre: 'Marta', apellido: 'Gómez', parentesco: 'Madre' }],
    });
    expect(JSON.stringify(result)).not.toMatch(/5555|marta@/);
  });

  it('requests athletes by page and size only, never with a family identifier', async () => {
    const athlete = backend.addAthlete({
      nombre: 'Ana',
      apellido: 'Paz',
      edad: 9,
      activo: true,
      // An over-sharing server (old, misconfigured): the client must still drop all of it, birth date included.
      extra: { fechaNacimiento: '2016-05-01', dni: '12345678', domicilio: 'Calle Falsa 123' },
    });
    backend.linkAthlete('familia-1', athlete.id);

    const page = await fetchAthletes({ page: 0, size: 10 });

    const url = new URL(portalRequests().at(-1)!.url, 'http://fake.local');
    expect([...url.searchParams.keys()].sort()).toEqual(['page', 'size']);
    // The age comes from the server as is; anything else (a birth date a server should not send) is dropped by validation.
    expect(page.contenido[0]).toEqual({ id: athlete.id, nombre: 'Ana', apellido: 'Paz', edad: 9, activo: true });
    expect(JSON.stringify(page)).not.toMatch(/12345678|Calle Falsa|2016-05-01|fechaNacimiento/); // an over-sharing server leaks nothing
  });

  it('the real list no longer sends the birth date, and if a server did, the client would not keep it', async () => {
    const normal = backend.addAthlete({ nombre: 'Ana', apellido: 'Paz', edad: 9, activo: true });
    const sobreComparte = backend.addAthlete({ nombre: 'Beto', apellido: 'Paz', edad: 7, activo: true, extra: { fechaNacimiento: '2018-02-02' } });
    backend.linkAthlete('familia-1', normal.id).linkAthlete('familia-1', sobreComparte.id);

    const raw = (await http.get<{ contenido: Record<string, unknown>[] }>('/familia/deportistas?page=0&size=10')).data;
    const parsed = await fetchAthletes({ page: 0, size: 10 });

    expect(Object.keys(raw.contenido[0]!).sort()).toEqual(['activo', 'apellido', 'edad', 'id', 'nombre']); // the contract after U4
    expect(raw.contenido[1]).toHaveProperty('fechaNacimiento'); // a misbehaving server
    for (const athlete of parsed.contenido) expect(Object.keys(athlete).sort()).toEqual(['activo', 'apellido', 'edad', 'id', 'nombre']);
  });

  it('keeps a null age as null and tolerates a server that does not send the age yet', async () => {
    const sinFecha = backend.addAthlete({ nombre: 'Ana', apellido: 'Paz', edad: null, activo: true });
    backend.linkAthlete('familia-1', sinFecha.id);
    expect((await fetchAthletes({ page: 0, size: 10 })).contenido[0]?.edad).toBeNull();

    // Frontend deployed before the backend (or an older backend): `edad` is absent. The list must not fail.
    backend.override('get', '/familia/deportistas?page=0&size=10', {
      status: 200,
      body: { contenido: [{ id: 'x1', nombre: 'Ana', apellido: 'Paz', activo: true }], pagina: 0, tamanio: 10, totalElementos: 1 },
    });
    const page = await fetchAthletes({ page: 0, size: 10 });
    expect(page.contenido[0]?.edad).toBeUndefined();
  });

  it('uses the real pagination: the page and the total come from the server', async () => {
    for (let i = 1; i <= 12; i += 1) {
      const a = backend.addAthlete({ nombre: `Atleta ${String(i).padStart(2, '0')}`, apellido: 'Prueba', fechaNacimiento: null, activo: true });
      backend.linkAthlete('familia-1', a.id);
    }

    const second = await fetchAthletes({ page: 1, size: 10 });

    expect(second.contenido.map((a) => a.nombre)).toEqual(['Atleta 11', 'Atleta 12']);
    expect(second).toMatchObject({ pagina: 1, tamanio: 10, totalElementos: 12 });
  });

  it('turns an unexpected response format into a server error', async () => {
    backend.override('get', '/familia/deportistas?page=0&size=10', { status: 200, body: { contenido: 'not-a-list' } });
    await expect(fetchAthletes({ page: 0, size: 10 })).rejects.toMatchObject({ kind: 'server' });
  });

  it('keeps "family not available" (404 FAMILIA_NO_ENCONTRADA) apart from every other error', async () => {
    backend.setPortalFamilyActive('familia-1', false);
    const unavailable = await fetchMyFamily().catch((e: unknown) => e);
    expect(isFamilyUnavailable(unavailable)).toBe(true);

    backend.override('get', '/familia/mi-familia', { status: 404, body: { codigo: 'OTRO', mensaje: 'x' } });
    const other = await fetchMyFamily().catch((e: unknown) => e);
    expect(isFamilyUnavailable(other)).toBe(false);
    expect(isFamilyUnavailable(new Error('x'))).toBe(false);
  });
});

describe('family error copy', () => {
  it.each([
    [new ApiError('network', 'x'), /No pudimos conectar/],
    [new ApiError('unavailable', 'x', { status: 503 }), /no está disponible/],
    [new ApiError('server', 'x', { status: 500 }), /problema en el servidor/],
    [new ApiError('forbidden', 'x', { status: 403, code: 'ACCESO_DENEGADO' }), /No tenés permiso/],
    [new ApiError('validation', 'x', { status: 400 }), /No pudimos cargar la información/],
  ])('maps %o to actionable copy', (error, expected) => {
    expect(familyErrorMessage(error)).toMatch(expected);
  });
});

describe('family portal hooks (mock backend)', () => {
  it('requests nothing for an account without a family', async () => {
    const client = createQueryClient();
    const noFamily = { userId: 'u-nofam', familyId: null };
    const mine = renderHook(() => useMyFamily(noFamily), { wrapper: wrapperFor(client) });
    const athletes = renderHook(() => useAthletes(noFamily, 0, 10), { wrapper: wrapperFor(client) });

    await waitFor(() => expect(mine.result.current.fetchStatus).toBe('idle'));
    expect(athletes.result.current.fetchStatus).toBe('idle');
    expect(portalRequests()).toHaveLength(0);
  });

  it('keeps the cache entries of two families apart', async () => {
    backend.addUser({ ...family, id: 'u-b', email: 'otra@example.com', familiaId: 'familia-2' });
    backend.addPortalFamily('familia-2', 'Familia López');
    const own = backend.addAthlete({ nombre: 'Propia', apellido: 'Gómez', fechaNacimiento: null, activo: true });
    const other = backend.addAthlete({ nombre: 'Ajena', apellido: 'López', fechaNacimiento: null, activo: true });
    backend.linkAthlete('familia-1', own.id);
    backend.linkAthlete('familia-2', other.id);
    const client = createQueryClient();
    const idA = { userId: 'u-family', familyId: 'familia-1' };
    const idB = { userId: 'u-b', familyId: 'familia-2' };

    const first = renderHook(() => useAthletes(idA, 0, 10), { wrapper: wrapperFor(client) });
    await waitFor(() => expect(first.result.current.data).toBeDefined());
    expect(first.result.current.data?.contenido.map((a) => a.nombre)).toEqual(['Propia']);

    backend.signIn('otra@example.com'); // same browser, another identity
    const second = renderHook(() => useAthletes(idB, 0, 10), { wrapper: wrapperFor(client) });
    await waitFor(() => expect(second.result.current.data).toBeDefined());
    expect(second.result.current.data?.contenido.map((a) => a.nombre)).toEqual(['Ajena']);

    expect(familyKeys.athletes(idA, 0, 10)).not.toEqual(familyKeys.athletes(idB, 0, 10));
    const cached = (id: typeof idA) => client.getQueryData<{ contenido: { nombre: string }[] }>(familyKeys.athletes(id, 0, 10))?.contenido.map((a) => a.nombre);
    expect(cached(idA)).toEqual(['Propia']);
    expect(cached(idB)).toEqual(['Ajena']);
  });

  it('one mounted observer: a new identity never receives the previous identity data, not even for one render', async () => {
    backend.addUser({ ...family, id: 'u-b', email: 'otra@example.com', familiaId: 'familia-2' });
    backend.addPortalFamily('familia-2', 'Familia López');
    backend.linkAthlete('familia-1', backend.addAthlete({ nombre: 'Propia', apellido: 'Gómez', fechaNacimiento: null, activo: true }).id);
    backend.linkAthlete('familia-2', backend.addAthlete({ nombre: 'Ajena', apellido: 'López', fechaNacimiento: null, activo: true }).id);
    const client = createQueryClient();
    const { result, rerender } = renderHook(({ identity }) => useAthletes(identity, 0, 10), {
      wrapper: wrapperFor(client),
      initialProps: { identity: { userId: 'u-family', familyId: 'familia-1' } },
    });
    await waitFor(() => expect(result.current.data).toBeDefined());

    backend.signIn('otra@example.com');
    rerender({ identity: { userId: 'u-b', familyId: 'familia-2' } });

    expect(result.current.data).toBeUndefined(); // the regression: this used to be family A's athletes
    expect(result.current.isPlaceholderData).toBe(false);
    await waitFor(() => expect(result.current.data?.contenido.map((a) => a.nombre)).toEqual(['Ajena']));
  });

  it('the query cache never holds a birth date, even if a server sent it', async () => {
    backend.linkAthlete('familia-1', backend.addAthlete({ nombre: 'Ana', apellido: 'Paz', edad: 9, activo: true, extra: { fechaNacimiento: '2016-05-01' } }).id);
    const client = createQueryClient();
    const identity = { userId: 'u-family', familyId: 'familia-1' };
    const { result } = renderHook(() => useAthletes(identity, 0, 10), { wrapper: wrapperFor(client) });
    await waitFor(() => expect(result.current.data).toBeDefined());

    const cached = JSON.stringify(client.getQueryCache().getAll().map((q) => q.state.data));
    expect(cached).toContain('"edad":9');
    expect(cached).not.toMatch(/fechaNacimiento|2016-05-01/);
  });

  it('two users of the same family do not share an entry either', () => {
    expect(familyKeys.athletes({ userId: 'u1', familyId: 'f1' }, 0, 10)).not.toEqual(familyKeys.athletes({ userId: 'u2', familyId: 'f1' }, 0, 10));
  });

  it('within one identity, paging keeps the previous page while the next one loads', async () => {
    for (let i = 1; i <= 12; i += 1) {
      backend.linkAthlete('familia-1', backend.addAthlete({ nombre: `Atleta ${String(i).padStart(2, '0')}`, apellido: 'Prueba', fechaNacimiento: null, activo: true }).id);
    }
    const client = createQueryClient();
    const identity = { userId: 'u-family', familyId: 'familia-1' };
    const { result, rerender } = renderHook(({ page }) => useAthletes(identity, page, 10), { wrapper: wrapperFor(client), initialProps: { page: 0 } });
    await waitFor(() => expect(result.current.data?.contenido).toHaveLength(10));

    rerender({ page: 1 });

    expect(result.current.isPlaceholderData).toBe(true);
    expect(result.current.data?.contenido).toHaveLength(10); // page 1 stays visible while page 2 loads
    await waitFor(() => expect(result.current.data?.contenido).toHaveLength(2));
  });
});

