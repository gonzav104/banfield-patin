import { act, render, screen, waitFor } from '@testing-library/react';
import type { QueryClient } from '@tanstack/react-query';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { AppProviders, createQueryClient } from '@/app/providers';
import { sessionKey } from '@/features/auth/session';
import type { FakeBackend } from '@/test/fakeBackend';
import { createBackend, family } from '@/test/render';
import { useFamilyAccount } from './account';
import { useAthleteResponsables } from './hooks';

// MOCK backend. The hook is mounted under the REAL session and providers. Silent identity change = /api/auth/me reports another person while the
// screen stays mounted (another tab signed in): the transitional render, the cache and late responses are what matters. Two families share ONE athlete.
let backend: FakeBackend;
const url = (id: string) => `/familia/deportistas/${id}/responsables`;
const requests = (id: string) => backend.requests.filter((r) => r.url === url(id)).length;

function Probe({ athleteId }: { athleteId: string }) {
  const account = useFamilyAccount();
  const query = useAthleteResponsables(account?.identity ?? null, athleteId);
  const text = query.isError ? `error:${(query.error as { status?: number }).status}` : query.data ? query.data.map((r) => `${r.nombre} ${r.apellido}`).join(', ') || 'vacío' : 'cargando';
  return <output data-testid="responsables">{text}</output>;
}

function mount(athleteId: string) {
  const queryClient = createQueryClient();
  render(
    <AppProviders client={queryClient}>
      <Probe athleteId={athleteId} />
    </AppProviders>,
  );
  return { queryClient };
}

const shown = () => screen.getByTestId('responsables').textContent;

async function silentIdentityChange(queryClient: QueryClient, email: string) {
  backend.signIn(email);
  await act(async () => {
    await queryClient.invalidateQueries({ queryKey: sessionKey });
  });
}

async function releaseAndSettle(held: { release: () => void }) {
  await act(async () => {
    held.release();
    await new Promise((resolve) => setTimeout(resolve, 30));
  });
}

const privateData = (queryClient: QueryClient) =>
  JSON.stringify(queryClient.getQueryCache().getAll().filter((q) => q.queryKey[0] === 'familia').map((q) => [q.queryKey, q.state.data ?? null]));

let shared: { id: string };

beforeEach(() => {
  backend = createBackend();
  backend.addUser({ ...family, id: 'u-b', nombre: 'Rosa', email: 'otra@example.com', familiaId: 'familia-2' });
  backend.addUser({ ...family, id: 'u-sis', nombre: 'Julia', email: 'hermana@example.com', familiaId: 'familia-1' });
  backend.addPortalFamily('familia-2', 'Familia López');
  shared = backend.addAthlete({ nombre: 'Ana', apellido: 'Paz', edad: 9, activo: true, vigente: null });
  backend.linkAthlete('familia-1', shared.id);
  backend.linkAthlete('familia-2', shared.id);
  backend.addResponsable(shared.id, 'familia-1', { nombre: 'Marta', apellido: 'Gómez', tipo: 'MADRE', tutorId: 't-a' });
  backend.addResponsable(shared.id, 'familia-2', { nombre: 'Luis', apellido: 'López', tipo: 'PADRE', tutorId: 't-b' });
  backend.signIn(family.email);
});

afterEach(() => backend.clearOverrides());

describe('responsables: the identity changes while the screen is mounted', () => {
  it('family A -> family B on the SAME shared athlete: nothing of A while B loads, then only B', async () => {
    const { queryClient } = mount(shared.id);
    await waitFor(() => expect(shown()).toBe('Marta Gómez'));

    const held = backend.hold('get', url(shared.id));
    await silentIdentityChange(queryClient, 'otra@example.com');
    await waitFor(() => expect(requests(shared.id)).toBe(2));
    expect(shown()).toBe('cargando'); // the transitional render: not a trace of A
    await releaseAndSettle(held);
    await waitFor(() => expect(shown()).toBe('Luis López'));
    expect(shown()).not.toMatch(/Marta|Gómez/);
    expect(privateData(queryClient)).not.toMatch(/Marta|Gómez/);
  });

  it('a late response of family A never lands on family B', async () => {
    const stale = backend.hold('get', url(shared.id)); // answered as A, delivered late
    const { queryClient } = mount(shared.id);
    await waitFor(() => expect(requests(shared.id)).toBe(1));

    await silentIdentityChange(queryClient, 'otra@example.com');
    await waitFor(() => expect(shown()).toBe('Luis López'));
    await releaseAndSettle(stale);
    expect(shown()).toBe('Luis López');
    expect(privateData(queryClient)).not.toMatch(/Marta|Gómez/);
  });

  it('another user of the SAME family asks again instead of reusing the previous user entry', async () => {
    const { queryClient } = mount(shared.id);
    await waitFor(() => expect(shown()).toBe('Marta Gómez'));
    const held = backend.hold('get', url(shared.id));
    await silentIdentityChange(queryClient, 'hermana@example.com');
    await waitFor(() => expect(requests(shared.id)).toBe(2));
    expect(shown()).toBe('cargando');
    await releaseAndSettle(held);
    await waitFor(() => expect(shown()).toBe('Marta Gómez'));
  });

  it('once the link of the family is revoked, a refresh ends in the uniform 404 and the previous list is not kept', async () => {
    const { queryClient } = mount(shared.id);
    await waitFor(() => expect(shown()).toBe('Marta Gómez'));
    backend.revokeLink('familia-1', shared.id);
    await act(async () => {
      await queryClient.invalidateQueries({ queryKey: ['familia'] });
    });
    await waitFor(() => expect(shown()).toBe('error:404'));
  });
});
