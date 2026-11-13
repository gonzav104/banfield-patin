import { act, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { QueryClient } from '@tanstack/react-query';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { createQueryClient } from '@/app/providers';
import type { AuthUser } from '@/features/auth/api';
import { dropPrivateStateIfIdentityChanged, sessionKey } from '@/features/auth/session';
import type { FakeBackend } from '@/test/fakeBackend';
import { createBackend, family, renderApp } from '@/test/render';

// MOCK backend. Regression tests for the leak found by the RDD review of FE-03/C1 (reliability lens, hooks.ts):
// when /api/auth/me silently updates the identity (another tab signed in as someone else) with the family screen
// still mounted, data of the previous identity must never reach the screen, the cache or a late response.
let backend: FakeBackend;

const LIST = '/familia/deportistas?page=0&size=10';
const listRequests = () => backend.requests.filter((r) => r.url === LIST).length;
const meRequests = () => backend.requests.filter((r) => r.url === '/auth/me').length;

beforeEach(() => {
  backend = createBackend();
  backend.addUser({ ...family, id: 'u-b', nombre: 'Rosa', email: 'otra@example.com', familiaId: 'familia-2' });
  backend.addUser({ ...family, id: 'u-sis', nombre: 'Julia', email: 'hermana@example.com', familiaId: 'familia-1' }); // same family as Lucía
  backend.addPortalFamily('familia-2', 'Familia López');
  backend.signIn(family.email);
});

afterEach(() => backend.clearOverrides());

function athlete(familyId: string, nombre: string, apellido: string) {
  const created = backend.addAthlete({ nombre, apellido, fechaNacimiento: null, activo: true });
  backend.linkAthlete(familyId, created.id);
  return created;
}

/** What /api/auth/me does when the shared cookie now belongs to another person and this tab re-checks. */
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

const cachedText = (queryClient: QueryClient) =>
  queryClient
    .getQueryCache()
    .getAll()
    .filter((q) => q.queryKey[0] !== sessionKey[0])
    .map((q) => JSON.stringify(q.state.data ?? null))
    .join('|');

describe('silent identity change with the athletes screen mounted', () => {
  it('family A -> family B: nothing of A is on screen while B loads, nor afterwards', async () => {
    athlete('familia-1', 'Propia', 'Gómez');
    athlete('familia-2', 'Ajena', 'López');
    const { queryClient } = renderApp('/familia/deportistas');
    await screen.findByText('Propia Gómez');

    const held = backend.hold('get', LIST);
    await silentIdentityChange(queryClient, 'otra@example.com');
    await waitFor(() => expect(listRequests()).toBe(2)); // B's own request is in flight

    expect(screen.queryByText('Propia Gómez')).not.toBeInTheDocument(); // the transitional render
    expect(screen.getByText('Cargando deportistas…')).toBeInTheDocument();

    await releaseAndSettle(held);
    expect(await screen.findByText('Ajena López')).toBeInTheDocument();
    expect(screen.queryByText('Propia Gómez')).not.toBeInTheDocument();
  });

  it('another user of the SAME family: does not reuse the previous user entry, it asks again', async () => {
    athlete('familia-1', 'Propia', 'Gómez');
    const { queryClient } = renderApp('/familia/deportistas');
    await screen.findByText('Propia Gómez');

    const held = backend.hold('get', LIST);
    await silentIdentityChange(queryClient, 'hermana@example.com');
    await waitFor(() => expect(listRequests()).toBe(2));

    expect(screen.queryByText('Propia Gómez')).not.toBeInTheDocument();
    await releaseAndSettle(held);
    expect(await screen.findByText('Propia Gómez')).toBeInTheDocument(); // same family, fetched for the new user
  });

  it('a late response of the previous identity never lands on the new one (different family)', async () => {
    athlete('familia-1', 'Propia', 'Gómez');
    athlete('familia-2', 'Ajena', 'López');
    const stale = backend.hold('get', LIST); // A's request: answered as A, delivered late
    const { queryClient } = renderApp('/familia/deportistas');
    await waitFor(() => expect(listRequests()).toBe(1));

    await silentIdentityChange(queryClient, 'otra@example.com');
    expect(await screen.findByText('Ajena López')).toBeInTheDocument();

    await releaseAndSettle(stale);
    expect(screen.getByText('Ajena López')).toBeInTheDocument();
    expect(screen.queryByText('Propia Gómez')).not.toBeInTheDocument();
    expect(cachedText(queryClient)).not.toContain('Propia');
  });

  it('a late response of the previous user never lands on the new one (same family)', async () => {
    const old = athlete('familia-1', 'Vieja', 'Gómez');
    const stale = backend.hold('get', LIST); // answered with the list as it was: [Vieja]
    const { queryClient } = renderApp('/familia/deportistas');
    await waitFor(() => expect(listRequests()).toBe(1));

    backend.revokeLink('familia-1', old.id);
    athlete('familia-1', 'Nueva', 'Gómez'); // the school changed the links meanwhile
    await silentIdentityChange(queryClient, 'hermana@example.com');
    expect(await screen.findByText('Nueva Gómez')).toBeInTheDocument();

    await releaseAndSettle(stale);
    expect(screen.getByText('Nueva Gómez')).toBeInTheDocument();
    expect(screen.queryByText('Vieja Gómez')).not.toBeInTheDocument();
    expect(cachedText(queryClient)).not.toContain('Vieja');
  });

  it('drops the private cache of the previous identity, keeps the session and does not loop on /me', async () => {
    athlete('familia-1', 'Propia', 'Gómez');
    athlete('familia-2', 'Ajena', 'López');
    const { queryClient } = renderApp('/familia/deportistas');
    await screen.findByText('Propia Gómez');
    const meBefore = meRequests();

    await silentIdentityChange(queryClient, 'otra@example.com');
    await screen.findByText('Ajena López');

    expect(cachedText(queryClient)).not.toContain('Propia');
    expect(queryClient.getQueryData(sessionKey)).toMatchObject({ id: 'u-b', familiaId: 'familia-2' });
    expect(meRequests()).toBe(meBefore + 1); // one re-check, no loop
    await act(async () => {
      await new Promise((resolve) => setTimeout(resolve, 50));
    });
    expect(meRequests()).toBe(meBefore + 1);
  });

  it('keeps pagination inside the same identity: the page does not carry over to the new one', async () => {
    for (let i = 1; i <= 12; i += 1) athlete('familia-1', `Atleta ${String(i).padStart(2, '0')}`, 'Gómez');
    for (let i = 1; i <= 12; i += 1) athlete('familia-2', `Socio ${String(i).padStart(2, '0')}`, 'López');
    const { queryClient } = renderApp('/familia/deportistas');
    await screen.findByText('Página 1 de 2 · 12 en total');
    await userEvent.setup().click(screen.getByRole('button', { name: 'Siguiente' }));
    await screen.findByText('Página 2 de 2 · 12 en total');

    await silentIdentityChange(queryClient, 'otra@example.com');

    expect(await screen.findByText('Página 1 de 2 · 12 en total')).toBeInTheDocument();
    expect(await screen.findByText('Socio 01 López')).toBeInTheDocument();
  });
});

describe('the same identity is left alone', () => {
  it('a /me re-check that returns the same person keeps the cache and does not refetch the list', async () => {
    athlete('familia-1', 'Propia', 'Gómez');
    const { queryClient } = renderApp('/familia/deportistas');
    await screen.findByText('Propia Gómez');
    const meBefore = meRequests();

    await act(async () => {
      await queryClient.invalidateQueries({ queryKey: sessionKey });
    });

    await waitFor(() => expect(meRequests()).toBe(meBefore + 1));
    expect(screen.getByText('Propia Gómez')).toBeInTheDocument();
    expect(cachedText(queryClient)).toContain('Propia');
    expect(listRequests()).toBe(1);
  });
});

describe('session identity reconciliation (unit)', () => {
  const user = (over: Partial<AuthUser> = {}): AuthUser => ({
    id: 'u1', nombre: 'A', apellido: 'A', email: 'a@example.com', rol: 'FAMILIA', escuelaId: 'e1', familiaId: 'f1', mfaPendiente: false, mfaEnrolado: false, ...over,
  });

  /** Starts a private request that only ends when it is aborted, and reports whether it was. */
  function pendingPrivateRequest(client: QueryClient) {
    const state = { aborted: false };
    void client
      .fetchQuery({
        queryKey: ['familia', 'u1:f1', 'deportistas'],
        queryFn: ({ signal }) =>
          new Promise((_, reject) => {
            signal.addEventListener('abort', () => {
              state.aborted = true;
              reject(new Error('aborted'));
            });
          }),
      })
      .catch(() => undefined);
    return state;
  }

  it.each([
    ['another user', { id: 'u2' }],
    ['another family', { familiaId: 'f2' }],
    ['another school', { escuelaId: 'e2' }],
    ['another role', { rol: 'ADMIN' as const }],
  ])('%s: aborts in-flight private requests and removes private data, keeping the session', (_label, change) => {
    const client = createQueryClient();
    client.setQueryData(sessionKey, user());
    client.setQueryData(['familia', 'u1:f1', 'mi-familia'], { id: 'f1' });
    const pending = pendingPrivateRequest(client);

    dropPrivateStateIfIdentityChanged(client, user(change));

    expect(pending.aborted).toBe(true);
    expect(client.getQueryCache().getAll().map((q) => q.queryKey[0])).toEqual(['session']);
    expect(client.getQueryData(sessionKey)).toMatchObject({ id: 'u1' }); // the session itself is left to the caller
  });

  it.each([
    ['the same person', {}],
    ['the same person with the second factor upgraded', { mfaPendiente: true, mfaEnrolado: true }],
  ])('%s: leaves everything alone', (_label, change) => {
    const client = createQueryClient();
    client.setQueryData(sessionKey, user());
    client.setQueryData(['familia', 'u1:f1', 'mi-familia'], { id: 'f1' });
    const pending = pendingPrivateRequest(client);

    dropPrivateStateIfIdentityChanged(client, user(change));

    expect(pending.aborted).toBe(false);
    expect(client.getQueryData(['familia', 'u1:f1', 'mi-familia'])).toEqual({ id: 'f1' });
  });

  it('does nothing on the very first load (no previous identity)', () => {
    const client = createQueryClient();
    client.setQueryData(['familia', 'u1:f1', 'mi-familia'], { id: 'f1' });
    dropPrivateStateIfIdentityChanged(client, user());
    expect(client.getQueryData(['familia', 'u1:f1', 'mi-familia'])).toEqual({ id: 'f1' });
  });
});

