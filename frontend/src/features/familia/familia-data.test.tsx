import { QueryClientProvider, type QueryClient } from '@tanstack/react-query';
import { renderHook, waitFor } from '@testing-library/react';
import type { ReactNode } from 'react';
import { beforeEach, describe, expect, it } from 'vitest';
import { createQueryClient } from '@/app/providers';
import { ApiError } from '@/lib/api-error';
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
    const athlete = backend.addAthlete({ nombre: 'Ana', apellido: 'Paz', fechaNacimiento: '2016-05-01', activo: true, extra: { dni: '12345678', domicilio: 'Calle Falsa 123' } });
    backend.linkAthlete('familia-1', athlete.id);

    const page = await fetchAthletes({ page: 0, size: 10 });

    const url = new URL(portalRequests().at(-1)!.url, 'http://fake.local');
    expect([...url.searchParams.keys()].sort()).toEqual(['page', 'size']);
    expect(page.contenido[0]).toEqual({ id: athlete.id, nombre: 'Ana', apellido: 'Paz', fechaNacimiento: '2016-05-01', activo: true });
    expect(JSON.stringify(page)).not.toMatch(/12345678|Calle Falsa/); // an over-sharing server still leaks nothing to the UI
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
    const mine = renderHook(() => useMyFamily(null), { wrapper: wrapperFor(client) });
    const athletes = renderHook(() => useAthletes(undefined, 0, 10), { wrapper: wrapperFor(client) });

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

    const first = renderHook(() => useAthletes('familia-1', 0, 10), { wrapper: wrapperFor(client) });
    await waitFor(() => expect(first.result.current.data).toBeDefined());
    expect(first.result.current.data?.contenido.map((a) => a.nombre)).toEqual(['Propia']);

    backend.signIn('otra@example.com'); // same browser, another identity
    const second = renderHook(() => useAthletes('familia-2', 0, 10), { wrapper: wrapperFor(client) });
    await waitFor(() => expect(second.result.current.data).toBeDefined());
    expect(second.result.current.data?.contenido.map((a) => a.nombre)).toEqual(['Ajena']);

    expect(familyKeys.athletes('familia-1', 0, 10)).not.toEqual(familyKeys.athletes('familia-2', 0, 10));
    const cached = (id: string) => client.getQueryData<{ contenido: { nombre: string }[] }>(familyKeys.athletes(id, 0, 10))?.contenido.map((a) => a.nombre);
    expect(cached('familia-1')).toEqual(['Propia']);
    expect(cached('familia-2')).toEqual(['Ajena']);
  });
});
