import { QueryClientProvider } from '@tanstack/react-query';
import { renderHook, waitFor } from '@testing-library/react';
import type { ReactNode } from 'react';
import { beforeEach, describe, expect, it } from 'vitest';
import { createQueryClient } from '@/app/providers';
import type { ApiError } from '@/lib/api-error';
import type { FakeBackend, FakeCurrentSeason } from '@/test/fakeBackend';
import { createBackend, family, admin } from '@/test/render';
import { fetchAthleteFicha, isAthleteId, isAthleteNotFound } from './api';
import { useAthleteFichaState } from './ficha-state';
import { familyKeys } from './hooks';
import type { FamilyIdentity } from './identity';

// MOCK backend (src/test/fakeBackend.ts). Real-backend evidence lives in e2e/portal.real.spec.ts.
let backend: FakeBackend;

const SEASON: FakeCurrentSeason = { anio: 2026, categoria: 'Sub 12', rama: 'Femenino', divisional: 'A', numeroCorredor: '007', estadoDeportivo: 'SUSPENDIDO' };
const IDENTITY: FamilyIdentity = { userId: 'u-family', familyId: 'familia-1' };
const ghost = '00000000-0000-4000-8000-0000000000ff'; // a well-formed id that does not exist
const sheetUrl = (id: string) => `/familia/deportistas/${id}/ficha`;
const portalUrls = () => backend.requests.filter((r) => r.url.startsWith('/familia/')).map((r) => r.url);

function own(over: Partial<Parameters<FakeBackend['addAthlete']>[0]> = {}) {
  const athlete = backend.addAthlete({ nombre: 'Ana', apellido: 'Paz', edad: 9, activo: true, vigente: SEASON, ...over });
  backend.linkAthlete('familia-1', athlete.id);
  return athlete;
}

beforeEach(() => {
  backend = createBackend();
  backend.signIn(family.email);
});

describe('minimal athlete sheet: contract (mock backend)', () => {
  it('parses the complete sheet exactly, with its closed list of fields', async () => {
    const athlete = own();

    const ficha = await fetchAthleteFicha(athlete.id);

    expect(ficha).toEqual({ nombre: 'Ana', apellido: 'Paz', edad: 9, activo: true, temporadaVigente: SEASON });
    expect(Object.keys(ficha).sort()).toEqual(['activo', 'apellido', 'edad', 'nombre', 'temporadaVigente']);
    expect(Object.keys(ficha.temporadaVigente!).sort()).toEqual(['anio', 'categoria', 'divisional', 'estadoDeportivo', 'numeroCorredor', 'rama']);
  });

  it('keeps every null the contract allows: no age, no current season, and free-text fields without data', async () => {
    const sinEdadNiTemporada = own({ nombre: 'Beto', edad: null, vigente: null });
    const textosNulos = own({ nombre: 'Cata', vigente: { anio: 2026, categoria: null, rama: null, divisional: null, numeroCorredor: null, estadoDeportivo: 'ACTIVO' } });

    expect(await fetchAthleteFicha(sinEdadNiTemporada.id)).toEqual({ nombre: 'Beto', apellido: 'Paz', edad: null, activo: true, temporadaVigente: null });
    expect((await fetchAthleteFicha(textosNulos.id)).temporadaVigente).toEqual({
      anio: 2026, categoria: null, rama: null, divisional: null, numeroCorredor: null, estadoDeportivo: 'ACTIVO',
    });
  });

  it('drops anything outside the closed list, even from an over-sharing server (sensitive data and internal ids)', async () => {
    const athlete = own({ extra: { dni: '12345678', cuil: '20-12345678-1', fechaNacimiento: '2016-05-01', domicilio: 'Calle Falsa 123', escuelaId: 'e1', familiaId: 'familia-1', telefonoContacto: '11-5555-0000' } });
    backend.override('get', sheetUrl(athlete.id), {
      status: 200,
      body: { nombre: 'Ana', apellido: 'Paz', edad: 9, activo: true, dni: '12345678', fechaNacimiento: '2016-05-01', temporadaVigente: { ...SEASON, temporadaId: 't-1', temporadaActiva: true, id: 'row-1' } },
    });

    const ficha = await fetchAthleteFicha(athlete.id);

    expect(Object.keys(ficha).sort()).toEqual(['activo', 'apellido', 'edad', 'nombre', 'temporadaVigente']);
    expect(Object.keys(ficha.temporadaVigente!).sort()).toEqual(['anio', 'categoria', 'divisional', 'estadoDeportivo', 'numeroCorredor', 'rama']);
    expect(JSON.stringify(ficha)).not.toMatch(/12345678|Calle Falsa|2016-05-01|t-1|row-1|fechaNacimiento|temporadaId|temporadaActiva/);
  });

  it('makes ONE request, to the sheet endpoint: never the 15-field detail, the seasons history or anything else', async () => {
    const athlete = own();

    await fetchAthleteFicha(athlete.id);

    expect(portalUrls()).toEqual([sheetUrl(athlete.id)]);
    expect(portalUrls()).not.toContain(`/familia/deportistas/${athlete.id}`);
    expect(portalUrls().some((url) => url.includes('/temporadas') || url.includes('?'))).toBe(false); // no query parameters
  });

  it.each(['SUSPENDIDO', 'ACTIVO', 'INACTIVO'] as const)('accepts the sports status %s as it is', async (estadoDeportivo) => {
    const athlete = own({ vigente: { ...SEASON, estadoDeportivo } });
    expect((await fetchAthleteFicha(athlete.id)).temporadaVigente?.estadoDeportivo).toBe(estadoDeportivo);
  });

  it('accepts the boundary values of the contract', async () => {
    const limites = own({ edad: 0, vigente: { anio: 2020, categoria: 'c'.repeat(80), rama: 'r'.repeat(40), divisional: 'd'.repeat(80), numeroCorredor: 'n'.repeat(30), estadoDeportivo: 'ACTIVO' } });
    const tope = own({ nombre: 'Dora', edad: 120, vigente: { ...SEASON, anio: 2100 } });

    expect((await fetchAthleteFicha(limites.id)).edad).toBe(0);
    expect((await fetchAthleteFicha(tope.id)).temporadaVigente?.anio).toBe(2100);
  });

  describe('values outside the contract are an unexpected format, never rendered', () => {
    const valid = { nombre: 'Ana', apellido: 'Paz', edad: 9, activo: true, temporadaVigente: SEASON };
    const withSeason = (changes: Record<string, unknown>) => ({ ...valid, temporadaVigente: { ...SEASON, ...changes } });
    it.each([
      ['an unknown sports status', withSeason({ estadoDeportivo: 'OTRO' })],
      ['a lower-case sports status', withSeason({ estadoDeportivo: 'activo' })],
      ['a missing sports status', withSeason({ estadoDeportivo: undefined })],
      ['a year before the contract range', withSeason({ anio: 2019 })],
      ['a year after the contract range', withSeason({ anio: 2101 })],
      ['a fractional year', withSeason({ anio: 2026.5 })],
      ['a year as text', withSeason({ anio: '2026' })],
      ['a category over 80 characters', withSeason({ categoria: 'c'.repeat(81) })],
      ['a branch over 40 characters', withSeason({ rama: 'r'.repeat(41) })],
      ['a division over 80 characters', withSeason({ divisional: 'd'.repeat(81) })],
      ['a runner number over 30 characters', withSeason({ numeroCorredor: 'n'.repeat(31) })],
      ['a runner number as a number', withSeason({ numeroCorredor: 7 })],
      ['a negative age', { ...valid, edad: -1 }],
      ['a fractional age', { ...valid, edad: 9.5 }],
      ['an age as text', { ...valid, edad: '9' }],
      ['a missing age', { nombre: 'Ana', apellido: 'Paz', activo: true, temporadaVigente: SEASON }],
      ['a missing current season (it must be explicit null)', { nombre: 'Ana', apellido: 'Paz', edad: 9, activo: true }],
      ['active as text', { ...valid, activo: 'true' }],
      ['a null name', { ...valid, nombre: null }],
      ['a list instead of an object', []],
    ])('rejects %s', async (_label, body) => {
      const athlete = own();
      backend.override('get', sheetUrl(athlete.id), { status: 200, body });

      await expect(fetchAthleteFicha(athlete.id)).rejects.toMatchObject({ kind: 'server' });
    });
  });

  it('never sends an id that is not a UUID: no request at all', async () => {
    for (const id of ['x', '123', '../mi-familia', 'ath-1', '', '00000000-0000-4000-8000-00000000000g', `${ghost}/temporadas`]) {
      await expect(fetchAthleteFicha(id)).rejects.toMatchObject({ kind: 'validation', status: 400, code: 'SOLICITUD_INVALIDA' });
      expect(isAthleteId(id)).toBe(false);
    }
    expect(isAthleteId(undefined)).toBe(false);
    expect(isAthleteId(ghost)).toBe(true);
    expect(portalUrls()).toEqual([]);
  });

  it('a foreign, non-existent or revoked athlete is the SAME 404; other 404s are not "athlete not found"', async () => {
    backend.addPortalFamily('familia-2', 'Otra');
    const ajeno = backend.addAthlete({ nombre: 'Beto', apellido: 'Lopez', activo: true });
    backend.linkAthlete('familia-2', ajeno.id);
    const revocado = own({ nombre: 'Rev' });
    backend.revokeLink('familia-1', revocado.id);

    const errors: unknown[] = [];
    for (const id of [ajeno.id, ghost, revocado.id]) errors.push(await fetchAthleteFicha(id).catch((e: unknown) => e));

    for (const error of errors) expect(isAthleteNotFound(error)).toBe(true);
    const [first, ...others] = errors as ApiError[];
    for (const other of others) expect([other.status, other.code, other.message]).toEqual([first!.status, first!.code, first!.message]);
    backend.override('get', sheetUrl(ghost), { status: 404, body: { codigo: 'RECURSO_NO_ENCONTRADO', mensaje: 'x' } });
    expect(isAthleteNotFound(await fetchAthleteFicha(ghost).catch((e: unknown) => e))).toBe(false);
    expect(isAthleteNotFound(new Error('x'))).toBe(false);
  });
});

describe('minimal athlete sheet: query keys', () => {
  it('carry user, family and athlete: nobody else shares an entry', () => {
    const base = familyKeys.ficha({ userId: 'u1', familyId: 'f1' }, 'a1');
    expect(base).toEqual(['familia', 'u1:f1', 'ficha', 'a1']);
    expect(familyKeys.ficha({ userId: 'u2', familyId: 'f1' }, 'a1')).not.toEqual(base); // another user, same family
    expect(familyKeys.ficha({ userId: 'u1', familyId: 'f2' }, 'a1')).not.toEqual(base); // same user, another family
    expect(familyKeys.ficha({ userId: 'u1', familyId: 'f1' }, 'a2')).not.toEqual(base); // another athlete
    expect(familyKeys.ficha(null, 'a1')[0]).toBe('familia'); // still a private key: the session code drops it
  });
});

describe('minimal athlete sheet: typed states', () => {
  function useState(identity: FamilyIdentity | null, id: string | undefined) {
    const client = createQueryClient();
    const wrapper = ({ children }: { children: ReactNode }) => <QueryClientProvider client={client}>{children}</QueryClientProvider>;
    return renderHook(() => useAthleteFichaState(identity, id), { wrapper });
  }

  it('without a valid FAMILIA session nothing is requested', () => {
    const athlete = own();
    const { result } = useState(null, athlete.id);
    expect(result.current).toEqual({ status: 'no-session' });
    expect(portalUrls()).toEqual([]);
  });

  it('an account without a family gets the not-found state and requests nothing', () => {
    const { result } = useState({ userId: 'u-nofam', familyId: null }, ghost);
    expect(result.current).toEqual({ status: 'not-found' });
    expect(portalUrls()).toEqual([]);
  });

  it('an id that is not a UUID is its own state and requests nothing', () => {
    const { result } = useState(IDENTITY, 'no-es-uuid');
    expect(result.current).toEqual({ status: 'invalid-id' });
    expect(useState(IDENTITY, undefined).result.current).toEqual({ status: 'invalid-id' });
    expect(portalUrls()).toEqual([]);
  });

  it('goes from loading to ready with the age text and the current season', async () => {
    const athlete = own();
    const { result } = useState(IDENTITY, athlete.id);
    expect(result.current).toEqual({ status: 'loading' });

    await waitFor(() => expect(result.current.status).toBe('ready'));

    expect(result.current).toMatchObject({ status: 'ready', ageText: '9 años', season: SEASON, ficha: { nombre: 'Ana', apellido: 'Paz', activo: true } });
  });

  it('no current season is just null, with no reason invented, and an unknown age says so', async () => {
    const athlete = own({ edad: null, vigente: null });
    const { result } = useState(IDENTITY, athlete.id);

    await waitFor(() => expect(result.current.status).toBe('ready'));

    expect(result.current).toMatchObject({ status: 'ready', ageText: 'Edad no informada', season: null });
    expect(Object.keys(result.current).sort()).toEqual(['ageText', 'ficha', 'season', 'status']); // no "reason" of any kind
  });

  it('the uniform 404 becomes not-found', async () => {
    const { result } = useState(IDENTITY, ghost);
    await waitFor(() => expect(result.current.status).toBe('not-found'));
  });

  it.each([
    ['a network failure', 'network', 'network'],
    ['the service being down (503)', { status: 503, body: { codigo: 'SERVICIO_NO_DISPONIBLE', mensaje: 'x' } }, 'unavailable'],
    ['a permission error (403)', { status: 403, body: { codigo: 'ACCESO_DENEGADO', mensaje: 'x' } }, 'forbidden'],
    ['an unexpected format', { status: 200, body: { nombre: 'Ana' } }, 'server'],
  ] as const)('%s is a retryable error and the retry recovers', async (_label, failure, kind) => {
    const athlete = own();
    backend.override('get', sheetUrl(athlete.id), failure, { once: true });
    const { result } = useState(IDENTITY, athlete.id);

    await waitFor(() => expect(result.current.status).toBe('error'));
    expect(result.current).toMatchObject({ status: 'error', error: { kind } });

    if (result.current.status === 'error') result.current.retry();
    await waitFor(() => expect(result.current.status).toBe('ready'));
  });

  it('a 401 is the expired-session state (the global session handling does the redirect)', async () => {
    const athlete = own();
    backend.override('get', sheetUrl(athlete.id), { status: 401, body: { codigo: 'NO_AUTENTICADO', mensaje: 'x' } });
    const { result } = useState(IDENTITY, athlete.id);

    await waitFor(() => expect(result.current.status).toBe('session-expired'));
  });

  it('an administrator session never reaches the sheet endpoint from the family account', () => {
    backend.signIn(admin.email);
    const athlete = own();
    const { result } = useState(null, athlete.id); // useFamilyAccount() is null for an administrator (see ficha-identity.test.tsx)
    expect(result.current.status).toBe('no-session');
    expect(portalUrls()).toEqual([]);
  });
});
