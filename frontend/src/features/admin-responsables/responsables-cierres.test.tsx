import { QueryClientProvider, type QueryClient } from '@tanstack/react-query';
import { act, cleanup, render, renderHook, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { ReactNode } from 'react';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { useChangeFamilyStatus, useChangeTutorStatus } from '@/features/admin-familias/hooks';
import { useChangeAthleteStatus } from '@/features/admin-deportistas/hooks';
import { useChangeLink } from '@/features/admin-vinculos/hooks';
import { seedAthlete } from '@/features/admin-vinculos/links-fixtures';
import type { FakeBackend } from '@/test/fakeBackend';
import { createBackend, renderApp } from '@/test/render';
import { clearAttempts } from './attempts';
import { useResponsables } from './hooks';

// MOCK backend. Closing a responsibility is not only a direct write: deactivating a tutor, a family or an athlete and revoking a family
// link close the open ones. Every such write must refresh the responsibility reads that are on screen.
let backend: FakeBackend;
const listGets = () => backend.requests.filter((r) => r.method === 'get' && /\/vinculos-tutores\?/.test(r.url));

beforeEach(() => {
  localStorage.clear();
  sessionStorage.clear();
  clearAttempts();
  backend = createBackend();
  Object.defineProperty(HTMLDialogElement.prototype, 'showModal', { configurable: true, value: function (this: HTMLDialogElement) { this.setAttribute('open', ''); } });
  Object.defineProperty(HTMLDialogElement.prototype, 'close', { configurable: true, value: function (this: HTMLDialogElement) { this.removeAttribute('open'); } });
});
afterEach(() => {
  cleanup();
  Reflect.deleteProperty(HTMLDialogElement.prototype, 'showModal');
  Reflect.deleteProperty(HTMLDialogElement.prototype, 'close');
});

function seed() {
  backend.setAltaResponsables(true);
  const athlete = seedAthlete(backend);
  const family = backend.addFamily('Familia Gómez');
  const link = backend.addAdminLink(family.id, athlete.id, { estado: 'ACTIVO', esPrincipal: true });
  const ana = backend.addTutorBehindTheScenes(family.id, { nombre: 'Ana', apellido: 'Alvarez', dni: '30111222' });
  const row = backend.addResponsable(athlete.id, family.id, { nombre: 'Ana', apellido: 'Alvarez', tipo: 'MADRE', tutorId: ana.id });
  return { athlete, family, link, ana, row };
}

function Probe({ athleteId }: { athleteId: string }) {
  const query = useResponsables(athleteId, false, 0, true);
  return <output data-testid="open">{query.data ? query.data.contenido.map((r) => r.nombreTutor).join(',') || 'vacío' : 'cargando'}</output>;
}

/** The app is signed in as the administrator, and a read of the open responsibilities stays observed next to it (as a screen would). */
async function observed(athleteId: string): Promise<{ queryClient: QueryClient; wrapper: (props: { children: ReactNode }) => ReactNode }> {
  backend.signIn('marcos@example.com');
  const { queryClient } = renderApp('/admin');
  await screen.findByRole('heading', { level: 1 });
  render(<QueryClientProvider client={queryClient}><Probe athleteId={athleteId} /></QueryClientProvider>);
  await waitFor(() => expect(screen.getByTestId('open')).toHaveTextContent('Ana'));
  return { queryClient, wrapper: ({ children }) => <QueryClientProvider client={queryClient}>{children}</QueryClientProvider> };
}

async function expectRefreshedEmpty(before: number) {
  await waitFor(() => expect(screen.getByTestId('open')).toHaveTextContent('vacío'));
  expect(listGets().length).toBeGreaterThan(before);
}

describe('direct and indirect closures refresh the responsables that are on screen', () => {
  it('deactivating a family', async () => {
    const { athlete, family } = seed();
    const { wrapper } = await observed(athlete.id);
    const before = listGets().length;
    const { result } = renderHook(() => useChangeFamilyStatus(), { wrapper });
    await act(async () => { await result.current.mutateAsync({ id: family.id, action: 'desactivar', version: String(family.version) }); });
    await expectRefreshedEmpty(before);
  });

  it('deactivating an athlete', async () => {
    const { athlete } = seed();
    const { wrapper } = await observed(athlete.id);
    const before = listGets().length;
    const { result } = renderHook(() => useChangeAthleteStatus(), { wrapper });
    await act(async () => { await result.current.mutateAsync({ id: athlete.id, action: 'desactivar', version: String(athlete.version) }); });
    await expectRefreshedEmpty(before);
  });

  it('revoking the family link', async () => {
    const { athlete, family, link } = seed();
    const { wrapper } = await observed(athlete.id);
    const before = listGets().length;
    const { result } = renderHook(() => useChangeLink(athlete.id), { wrapper });
    await act(async () => {
      await result.current.mutateAsync({ athleteId: athlete.id, familyId: family.id, linkId: link.id, action: 'revocar', version: String(athlete.version), familyIds: [family.id] });
    });
    await expectRefreshedEmpty(before);
  });

  it('deactivating a tutor', async () => {
    const { athlete, family, ana } = seed();
    const { wrapper } = await observed(athlete.id);
    const before = listGets().length;
    const { result } = renderHook(() => useChangeTutorStatus(), { wrapper });
    await act(async () => { await result.current.mutateAsync({ id: ana.id, familyId: family.id, action: 'desactivar', version: '0' }); });
    await expectRefreshedEmpty(before);
  });
});

describe('tutor detail: activate and deactivate', () => {
  const tutorPath = (familyId: string, tutorId: string) => `/admin/familias/${familyId}/tutores/${tutorId}`;
  const statusPosts = () => backend.requests.filter((r) => r.method === 'post' && /\/admin\/tutores\/[^/]+\/(activar|desactivar)$/.test(r.url));

  it('explains the irreversible closure, cancelling writes nothing and confirming deactivates with the revision shown', async () => {
    const { athlete, family, ana } = seed();
    backend.signIn('marcos@example.com');
    renderApp(tutorPath(family.id, ana.id));
    await userEvent.click(await screen.findByRole('button', { name: 'Desactivar tutor' }));
    let dialog = await screen.findByRole('dialog', { name: 'Desactivar tutor' });
    expect(dialog).toHaveTextContent(/irreversible/);
    expect(dialog).toHaveTextContent(/no las reabre/);
    await userEvent.click(within(dialog).getByRole('button', { name: 'Cancelar' }));
    expect(statusPosts()).toEqual([]);
    expect(screen.getByRole('button', { name: 'Desactivar tutor' })).toHaveFocus();

    await userEvent.click(screen.getByRole('button', { name: 'Desactivar tutor' }));
    dialog = await screen.findByRole('dialog', { name: 'Desactivar tutor' });
    await userEvent.click(within(dialog).getByRole('button', { name: 'Confirmar desactivación' }));
    expect(await screen.findByText(/quedó desactivado y sus responsabilidades vigentes se cerraron/)).toBeInTheDocument();
    expect(statusPosts()[0]!.body).toEqual({ versionEsperada: '0' });
    expect(screen.getByRole('button', { name: 'Reactivar tutor' })).toBeInTheDocument();
    expect(backend.responsablesOf(athlete.id)[0]).toMatchObject({ causaCierre: 'TUTOR_INACTIVO' });
  });

  it('reactivating the tutor never reopens its closed responsibility', async () => {
    const { athlete, family, ana } = seed();
    backend.signIn('marcos@example.com');
    renderApp(tutorPath(family.id, ana.id));
    await userEvent.click(await screen.findByRole('button', { name: 'Desactivar tutor' }));
    await userEvent.click(within(await screen.findByRole('dialog', { name: 'Desactivar tutor' })).getByRole('button', { name: 'Confirmar desactivación' }));
    await userEvent.click(await screen.findByRole('button', { name: 'Reactivar tutor' }));
    await userEvent.click(within(await screen.findByRole('dialog', { name: 'Reactivar tutor' })).getByRole('button', { name: 'Confirmar reactivación' }));
    expect(await screen.findByText(/Sus responsabilidades cerradas no se reabren/)).toBeInTheDocument();
    expect(backend.responsablesOf(athlete.id)[0]!.cerradoEn).not.toBeNull();
    expect(statusPosts().map((r) => r.body)).toEqual([{ versionEsperada: '0' }, { versionEsperada: '1' }]);
  });

  it('a stale revision applies nothing and says so', async () => {
    const { family, ana } = seed();
    backend.signIn('marcos@example.com');
    renderApp(tutorPath(family.id, ana.id));
    await userEvent.click(await screen.findByRole('button', { name: 'Desactivar tutor' }));
    const dialog = await screen.findByRole('dialog', { name: 'Desactivar tutor' });
    ana.version = (ana.version ?? 0) + 1; // another administrator edited the tutor meanwhile
    await userEvent.click(within(dialog).getByRole('button', { name: 'Confirmar desactivación' }));
    expect(await within(dialog).findByText(/cambiaron mientras decidías/)).toBeInTheDocument();
    expect(ana.activo).toBe(true);
  });

  it('a lost answer is verified by consulting and never repeated', async () => {
    const { family, ana } = seed();
    backend.override('post', `/admin/tutores/${ana.id}/desactivar`, 'lost', { once: true });
    backend.signIn('marcos@example.com');
    renderApp(tutorPath(family.id, ana.id));
    await userEvent.click(await screen.findByRole('button', { name: 'Desactivar tutor' }));
    const dialog = await screen.findByRole('dialog', { name: 'Desactivar tutor' });
    await userEvent.click(within(dialog).getByRole('button', { name: 'Confirmar desactivación' }));
    expect(await within(dialog).findByText(/No podemos confirmar el resultado/)).toBeInTheDocument();
    expect(within(dialog).queryByRole('button', { name: 'Confirmar desactivación' })).not.toBeInTheDocument();
    await userEvent.click(within(dialog).getByRole('button', { name: 'Consultar estado vigente' }));
    await waitFor(() => expect(screen.getByRole('button', { name: 'Reactivar tutor' })).toBeInTheDocument());
    expect(statusPosts()).toHaveLength(1);
  });

  it('if consulting after a lost answer fails too, the page shows its read error with a retry and never claims the state was shown', async () => {
    const { family, ana } = seed();
    backend.override('post', `/admin/tutores/${ana.id}/desactivar`, 'lost', { once: true });
    backend.signIn('marcos@example.com');
    renderApp(tutorPath(family.id, ana.id));
    await userEvent.click(await screen.findByRole('button', { name: 'Desactivar tutor' }));
    const dialog = await screen.findByRole('dialog', { name: 'Desactivar tutor' });
    await userEvent.click(within(dialog).getByRole('button', { name: 'Confirmar desactivación' }));
    await within(dialog).findByText(/No podemos confirmar el resultado/);
    backend.override('get', `/admin/tutores/${ana.id}`, { status: 500, body: { codigo: 'ERROR', mensaje: 'x' } }, { once: true });
    await userEvent.click(within(dialog).getByRole('button', { name: 'Consultar estado vigente' }));
    expect(await screen.findByRole('alert')).toBeInTheDocument();
    expect(screen.queryByText('Mostramos el estado vigente del tutor.')).not.toBeInTheDocument();
    expect(statusPosts()).toHaveLength(1); // never repeated
    await userEvent.click(screen.getByRole('button', { name: 'Reintentar' }));
    expect(await screen.findByRole('button', { name: 'Reactivar tutor' })).toBeInTheDocument(); // the lost write had really happened
    expect(statusPosts()).toHaveLength(1);
  });
});
