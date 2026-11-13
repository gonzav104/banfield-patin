import { act, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { QueryClient } from '@tanstack/react-query';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { AppProviders, createQueryClient } from '@/app/providers';
import { sessionKey } from '@/features/auth/session';
import type { FakeBackend, FakeCurrentSeason } from '@/test/fakeBackend';
import { admin, createBackend, family } from '@/test/render';
import { useFamilyAccount } from './account';
import { useAthleteFichaState } from './ficha-state';

// MOCK backend. The sheet is mounted under the REAL session and providers (the screen itself comes later): a probe reads
// the typed state exactly as the screen will. Silent identity change = /api/auth/me reports another person while the
// sheet stays mounted (another tab signed in); the transitional render, the cache and late responses are what matters.
let backend: FakeBackend;

const SEASON: FakeCurrentSeason = { anio: 2026, categoria: 'Sub 12', rama: 'Femenino', divisional: 'A', numeroCorredor: '007', estadoDeportivo: 'ACTIVO' };
const ghost = '00000000-0000-4000-8000-0000000000ff';
const sheetUrl = (id: string) => `/familia/deportistas/${id}/ficha`;
const sheetRequests = (id: string) => backend.requests.filter((r) => r.url === sheetUrl(id)).length;
const familyRequests = () => backend.requests.filter((r) => r.url.startsWith('/familia/')).length;
const meRequests = () => backend.requests.filter((r) => r.url === '/auth/me').length;

function Probe({ athleteId }: { athleteId: string }) {
  const account = useFamilyAccount();
  const state = useAthleteFichaState(account?.identity ?? null, athleteId);
  const text = state.status === 'ready' ? `${state.ficha.nombre} ${state.ficha.apellido} | ${state.ageText} | ${state.season?.categoria ?? 'sin-temporada'}` : state.status;
  return (
    <div>
      <output data-testid="ficha">{text}</output>
      {state.status === 'error' ? <button onClick={state.retry}>Reintentar</button> : null}
    </div>
  );
}

function mount(athleteId: string) {
  const queryClient = createQueryClient();
  const ui = (id: string) => (
    <AppProviders client={queryClient}>
      <Probe athleteId={id} />
    </AppProviders>
  );
  const view = render(ui(athleteId));
  return { queryClient, show: (id: string) => view.rerender(ui(id)) };
}

const shown = () => screen.getByTestId('ficha').textContent;

async function silentIdentityChange(queryClient: QueryClient, email: string) {
  backend.signIn(email);
  await act(async () => {
    await queryClient.invalidateQueries({ queryKey: sessionKey });
  });
}

/** Delivers a held response and gives it real time to reach (or be discarded by) the client. */
async function releaseAndSettle(held: { release: () => void }) {
  await act(async () => {
    held.release();
    await new Promise((resolve) => setTimeout(resolve, 30));
  });
}

const privateData = (queryClient: QueryClient) =>
  JSON.stringify(queryClient.getQueryCache().getAll().filter((q) => q.queryKey[0] === 'familia').map((q) => [q.queryKey, q.state.data ?? null]));

let own: { id: string; vigente?: FakeCurrentSeason | null };
let other: { id: string };

beforeEach(() => {
  backend = createBackend();
  backend.addUser({ ...family, id: 'u-b', nombre: 'Rosa', email: 'otra@example.com', familiaId: 'familia-2' });
  backend.addUser({ ...family, id: 'u-sis', nombre: 'Julia', email: 'hermana@example.com', familiaId: 'familia-1' }); // same family as Lucía
  backend.addPortalFamily('familia-2', 'Familia López');
  own = backend.addAthlete({ nombre: 'Ana', apellido: 'Paz', edad: 9, activo: true, vigente: SEASON });
  backend.linkAthlete('familia-1', own.id);
  other = backend.addAthlete({ nombre: 'Beto', apellido: 'López', edad: 7, activo: true, vigente: { ...SEASON, categoria: 'Sub 8' } });
  backend.linkAthlete('familia-2', other.id);
  backend.signIn(family.email);
});

afterEach(() => backend.clearOverrides());

describe('sheet mounted, identity changes silently', () => {
  it('family A -> family B: nothing of A while B loads, then the uniform 404 for A athlete', async () => {
    const { queryClient } = mount(own.id);
    await waitFor(() => expect(shown()).toBe('Ana Paz | 9 años | Sub 12'));

    const held = backend.hold('get', sheetUrl(own.id));
    await silentIdentityChange(queryClient, 'otra@example.com');
    await waitFor(() => expect(sheetRequests(own.id)).toBe(2)); // B's own request is in flight

    expect(shown()).toBe('loading'); // the transitional render: not a trace of A
    await releaseAndSettle(held);
    await waitFor(() => expect(shown()).toBe('not-found'));
    expect(shown()).not.toMatch(/Ana|Sub 12/);
  });

  it('another user of the SAME family: the previous user entry is not reused, it asks again', async () => {
    const { queryClient } = mount(own.id);
    await waitFor(() => expect(shown()).toBe('Ana Paz | 9 años | Sub 12'));

    const held = backend.hold('get', sheetUrl(own.id));
    await silentIdentityChange(queryClient, 'hermana@example.com');
    await waitFor(() => expect(sheetRequests(own.id)).toBe(2));

    expect(shown()).toBe('loading');
    await releaseAndSettle(held);
    await waitFor(() => expect(shown()).toBe('Ana Paz | 9 años | Sub 12')); // same family: fetched again for the new user
  });

  it('a late response of the previous identity (another family) never lands on the new one', async () => {
    const stale = backend.hold('get', sheetUrl(own.id)); // A's request: answered as A, delivered late
    const { queryClient } = mount(own.id);
    await waitFor(() => expect(sheetRequests(own.id)).toBe(1));

    await silentIdentityChange(queryClient, 'otra@example.com');
    await waitFor(() => expect(shown()).toBe('not-found')); // B asked for the same id: the uniform 404

    await releaseAndSettle(stale);
    expect(shown()).toBe('not-found');
    expect(privateData(queryClient)).not.toMatch(/Ana|Sub 12/);
  });

  it('a late response of the previous USER of the same family never lands on the new one', async () => {
    const stale = backend.hold('get', sheetUrl(own.id)); // answered with the data as it was: Sub 12
    const { queryClient } = mount(own.id);
    await waitFor(() => expect(sheetRequests(own.id)).toBe(1));
    own.vigente = { ...SEASON, categoria: 'Sub 14' }; // the school changed the data meanwhile

    await silentIdentityChange(queryClient, 'hermana@example.com');
    await waitFor(() => expect(shown()).toBe('Ana Paz | 9 años | Sub 14'));

    await releaseAndSettle(stale);
    expect(shown()).toBe('Ana Paz | 9 años | Sub 14');
    expect(privateData(queryClient)).not.toContain('Sub 12');
  });

  it('drops the private cache of the previous identity, keeps the session and does not loop on /me', async () => {
    const { queryClient } = mount(own.id);
    await waitFor(() => expect(shown()).toBe('Ana Paz | 9 años | Sub 12'));
    const meBefore = meRequests();

    await silentIdentityChange(queryClient, 'otra@example.com');
    await waitFor(() => expect(shown()).toBe('not-found'));

    const keys = queryClient.getQueryCache().getAll().filter((q) => q.queryKey[0] === 'familia').map((q) => String(q.queryKey[1]));
    expect(keys.length).toBeGreaterThan(0);
    expect(keys.every((key) => key.startsWith('u-b:'))).toBe(true); // nothing keyed to the previous identity survives
    expect(privateData(queryClient)).not.toMatch(/Ana|Sub 12|u-family/);
    expect(queryClient.getQueryData(sessionKey)).toMatchObject({ id: 'u-b' });
    await act(async () => {
      await new Promise((resolve) => setTimeout(resolve, 50));
    });
    expect(meRequests()).toBe(meBefore + 1); // one re-check, no loop
  });
});

describe('sheet mounted, another athlete with the same identity', () => {
  it('shows nothing of the previous athlete while the new one loads, and keeps each entry apart', async () => {
    const second = backend.addAthlete({ nombre: 'Cata', apellido: 'Paz', edad: null, activo: false, vigente: null });
    backend.linkAthlete('familia-1', second.id);
    const { show } = mount(own.id);
    await waitFor(() => expect(shown()).toBe('Ana Paz | 9 años | Sub 12'));

    const held = backend.hold('get', sheetUrl(second.id));
    show(second.id);
    await waitFor(() => expect(sheetRequests(second.id)).toBe(1));

    expect(shown()).toBe('loading'); // not Ana: no placeholder from another athlete
    await releaseAndSettle(held);
    await waitFor(() => expect(shown()).toBe('Cata Paz | Edad no informada | sin-temporada'));

    show(own.id); // back to the first: its OWN cached entry, not the second one
    expect(shown()).toBe('Ana Paz | 9 años | Sub 12');
    expect(sheetRequests(own.id)).toBe(1); // fresh within the same identity: no new request
  });

  it('a late response of the athlete left behind does not replace the one now shown', async () => {
    const second = backend.addAthlete({ nombre: 'Cata', apellido: 'Paz', edad: 5, activo: true, vigente: null });
    backend.linkAthlete('familia-1', second.id);
    const stale = backend.hold('get', sheetUrl(own.id));
    const { show } = mount(own.id);
    await waitFor(() => expect(sheetRequests(own.id)).toBe(1));

    show(second.id);
    await waitFor(() => expect(shown()).toBe('Cata Paz | 5 años | sin-temporada'));
    await releaseAndSettle(stale);

    expect(shown()).toBe('Cata Paz | 5 años | sin-temporada');
  });
});

describe('who may ask, and what is left behind', () => {
  it('no query for an administrator: the state is no-session and nothing is requested', async () => {
    backend.signIn(admin.email);
    mount(own.id);

    await waitFor(() => expect(meRequests()).toBeGreaterThan(0));
    await act(async () => {
      await new Promise((resolve) => setTimeout(resolve, 30));
    });
    expect(shown()).toBe('no-session');
    expect(familyRequests()).toBe(0);
  });

  it('no query for an anonymous visitor either', async () => {
    backend.expireSession(); // no session at all
    mount(own.id);

    await waitFor(() => expect(meRequests()).toBeGreaterThan(0));
    await act(async () => {
      await new Promise((resolve) => setTimeout(resolve, 30));
    });
    expect(shown()).toBe('no-session');
    expect(familyRequests()).toBe(0);
  });

  it('an account without a family is not-found and requests nothing', async () => {
    backend.addUser({ ...family, id: 'u-nofam', email: 'sinfamilia@example.com', familiaId: null });
    backend.signIn('sinfamilia@example.com');
    mount(own.id);

    await waitFor(() => expect(shown()).toBe('not-found'));
    expect(familyRequests()).toBe(0);
  });

  it('an id that is not a UUID is invalid-id and requests nothing', async () => {
    mount('no-es-uuid');

    await waitFor(() => expect(shown()).toBe('invalid-id'));
    expect(familyRequests()).toBe(0);
  });

  it('nothing is written to localStorage or sessionStorage', async () => {
    mount(own.id);
    await waitFor(() => expect(shown()).toBe('Ana Paz | 9 años | Sub 12'));

    expect({ local: Object.keys(localStorage), session: Object.keys(sessionStorage) }).toEqual({ local: [], session: [] });
  });

  it('an expired session (401) falls back to the existing handling: the session is cleared and the sheet is no longer requested', async () => {
    backend.override('get', sheetUrl(own.id), { status: 401, body: { codigo: 'NO_AUTENTICADO', mensaje: 'x' } });
    const { queryClient } = mount(own.id);

    await waitFor(() => expect(sheetRequests(own.id)).toBe(1)); // the session resolved and the sheet was asked for
    await waitFor(() => expect(queryClient.getQueryData(sessionKey)).toBeNull()); // the global handling cleared the session
    expect(shown()).toBe('no-session');
    expect(privateData(queryClient)).not.toContain('Ana');
  });

  it('a failure keeps the session and the retry button recovers', async () => {
    backend.override('get', sheetUrl(own.id), { status: 503, body: { codigo: 'SERVICIO_NO_DISPONIBLE', mensaje: 'x' } }, { once: true });
    const { queryClient } = mount(own.id);

    await waitFor(() => expect(shown()).toBe('error'));
    expect(queryClient.getQueryData(sessionKey)).toMatchObject({ id: 'u-family' }); // never closes the session
    await userEvent.setup().click(screen.getByRole('button', { name: 'Reintentar' }));
    await waitFor(() => expect(shown()).toBe('Ana Paz | 9 años | Sub 12'));
  });

  it('never calls the 15-field detail, the seasons history or the list for the sheet', async () => {
    mount(own.id);
    await waitFor(() => expect(shown()).toBe('Ana Paz | 9 años | Sub 12'));

    const urls = backend.requests.filter((r) => r.url.startsWith('/familia/')).map((r) => r.url);
    expect(urls).toEqual([sheetUrl(own.id)]);
  });
});

// A uniform 404 also for an id nobody owns: same state as a foreign athlete.
it('a well-formed id nobody owns is the same not-found state', async () => {
  mount(ghost);
  await waitFor(() => expect(shown()).toBe('not-found'));
});
