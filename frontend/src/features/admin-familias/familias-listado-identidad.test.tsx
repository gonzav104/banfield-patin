import { act, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { AxiosError, type AxiosAdapter } from 'axios';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { sessionKey } from '@/features/auth/session';
import { http } from '@/lib/http';
import type { FakeBackend } from '@/test/fakeBackend';
import { admin, createBackend, renderApp } from '@/test/render';

// Maintenance: the administrative family list when the session changes to ANOTHER administrator (mock backend, see
// src/test/fakeBackend.ts). The search box can hold a surname: it must never outlive the identity that typed it.
let backend: FakeBackend;

const realAdapter = http.defaults.adapter;
afterEach(() => {
  http.defaults.adapter = realAdapter;
});

beforeEach(() => {
  localStorage.clear();
  sessionStorage.clear();
  backend = createBackend();
  backend.addUser({ ...admin, id: 'u-admin-3', nombre: 'Otra', email: 'otra.admin@example.com', mfaEnrolado: true });
});

const OTHER = 'otra.admin@example.com';
const UNFILTERED = '/admin/familias/listado?page=0&size=20';
const SEARCHED = '/admin/familias/listado?page=0&size=20&busqueda=pere';
const listRequests = () => backend.requests.filter((r) => r.method === 'get' && r.url.startsWith('/admin/familias/listado'));
const location = () => screen.getByTestId('location').textContent ?? '';
const box = () => screen.getByRole('searchbox', { name: 'Buscar por nombre' });

async function open(path = '/admin/familias') {
  backend.addFamily('Gómez');
  backend.addFamily('Pereira');
  backend.addFamily('Pereyra');
  backend.signIn('marcos@example.com');
  const { queryClient } = renderApp(path);
  await screen.findByRole('heading', { name: 'Familias' });
  return { queryClient, user: userEvent.setup() };
}

const sleep = (ms: number) => act(async () => new Promise((resolve) => setTimeout(resolve, ms)));

/** `backend.hold` only delays answers computed by the mock; an ERROR outcome needs the adapter itself to be wrapped. */
function failLater(url: string) {
  let open!: () => void;
  const gate = new Promise<void>((resolve) => (open = resolve));
  let used = false;
  const inner = http.defaults.adapter as AxiosAdapter;
  http.defaults.adapter = ((config) => {
    if (used || config.url !== url) return inner(config);
    used = true;
    return gate.then(() => {
      const response = { data: { codigo: 'ERROR_INTERNO', mensaje: 'detalle' }, status: 500, statusText: '500', headers: {}, config, request: {} };
      return Promise.reject(new AxiosError('x', 'ERR_BAD_RESPONSE', config, {}, response));
    });
  }) as AxiosAdapter;
  return { release: open, started: () => used };
}

async function switchIdentity(queryClient: Awaited<ReturnType<typeof open>>['queryClient']) {
  backend.signIn(OTHER);
  await act(async () => {
    await queryClient.invalidateQueries({ queryKey: sessionKey });
  });
}

describe('family list: identity change (mock backend)', () => {
  it('clears a COMPLETED search: the term, the filtered rows and no old rows while the new identity\'s query resolves', async () => {
    const { queryClient, user } = await open();
    await screen.findByText('Gómez');
    await user.type(box(), 'pere');
    await waitFor(() => expect(screen.queryByText('Gómez')).not.toBeInTheDocument());
    expect(screen.getByText('Pereira')).toBeInTheDocument();
    const held = backend.hold('get', UNFILTERED);
    await switchIdentity(queryClient);
    expect(await screen.findByText('Cargando familias…')).toBeInTheDocument(); // no old rows while the new query resolves
    expect(screen.queryByText('Pereira')).not.toBeInTheDocument();
    expect(box()).toHaveValue('');
    held.release();
    expect(await screen.findByText('Gómez')).toBeInTheDocument(); // the new identity's own, unfiltered query
    expect(screen.getByText('Pereira')).toBeInTheDocument();
    expect(screen.getByText('Pereyra')).toBeInTheDocument();
    expect(box()).toHaveValue('');
    expect(listRequests().at(-1)!.url).not.toContain('busqueda'); // the previous term never travels again
  });

  it('discards the response of a PENDING search of the previous administrator: it never populates the new screen', async () => {
    const { queryClient, user } = await open();
    await screen.findByText('Gómez');
    const stale = backend.hold('get', SEARCHED);
    await user.type(box(), 'pere');
    await waitFor(() => expect(listRequests().some((r) => r.url === SEARCHED)).toBe(true)); // the search is in flight
    const fresh = backend.hold('get', UNFILTERED);
    await switchIdentity(queryClient);
    expect(await screen.findByText('Cargando familias…')).toBeInTheDocument();
    expect(box()).toHaveValue('');
    stale.release(); // the old answer (only the «pere» rows) arrives while the new identity's query is still pending
    await act(async () => new Promise((resolve) => setTimeout(resolve, 150)));
    expect(screen.getByText('Cargando familias…')).toBeInTheDocument();
    expect(screen.queryByText('Pereira')).not.toBeInTheDocument();
    fresh.release();
    expect(await screen.findByText('Gómez')).toBeInTheDocument();
    expect(screen.getByText('3 familias')).toBeInTheDocument(); // the full list of the new identity, not the filtered one
    expect(box()).toHaveValue('');
  });

  it('keeps the term out of the address and of the storage, before and after the change', async () => {
    const { queryClient, user } = await open();
    await screen.findByText('Gómez');
    await user.type(box(), 'pere');
    await waitFor(() => expect(listRequests().some((r) => r.url === SEARCHED)).toBe(true));
    expect(location()).not.toMatch(/pere/i);
    expect(JSON.stringify({ ...localStorage, ...sessionStorage })).not.toMatch(/pere/i);
    await switchIdentity(queryClient);
    await screen.findByText('Gómez');
    expect(location()).not.toMatch(/pere/i);
    expect(JSON.stringify({ ...localStorage, ...sessionStorage })).not.toMatch(/pere/i);
  });

  it('keeps the status filter and the page of the address (they are not personal) while the term and the rows are dropped', async () => {
    backend.addFamily('Inactiva Uno', { activa: false });
    const { queryClient, user } = await open('/admin/familias?estado=INACTIVOS');
    expect(await screen.findByText('Inactiva Uno')).toBeInTheDocument();
    expect(screen.getByLabelText('Estado')).toHaveValue('INACTIVOS');
    await user.type(box(), 'inact');
    await waitFor(() => expect(listRequests().some((r) => r.url.includes('busqueda=inact'))).toBe(true));
    await switchIdentity(queryClient);
    await screen.findByText('Inactiva Uno');
    expect(box()).toHaveValue('');
    expect(screen.getByLabelText('Estado')).toHaveValue('INACTIVOS');
    expect(location()).toBe('/admin/familias?estado=INACTIVOS');
    expect(listRequests().at(-1)!.url).toBe('/admin/familias/listado?page=0&size=20&estado=INACTIVOS');
  });

  it('still searches, filters and pages normally after the identity changed (the new identity\'s own screen works)', async () => {
    const { queryClient, user } = await open();
    await screen.findByText('Gómez');
    await switchIdentity(queryClient);
    await screen.findByText('Gómez');
    await user.type(box(), 'góm');
    await waitFor(() => expect(screen.queryByText('Pereira')).not.toBeInTheDocument());
    expect(screen.getByText('Gómez')).toBeInTheDocument();
    await user.selectOptions(screen.getByLabelText('Estado'), 'INACTIVOS');
    expect(await screen.findByText('No encontramos familias con esos criterios.')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Limpiar filtros' }));
    expect(await screen.findByText('Pereira')).toBeInTheDocument();
  });

  it('does NOT reset anything when the session is re-checked for the SAME administrator (only a different identity resets the screen)', async () => {
    const { queryClient, user } = await open();
    await screen.findByText('Gómez');
    await user.type(box(), 'pere');
    await waitFor(() => expect(screen.queryByText('Gómez')).not.toBeInTheDocument());
    await act(async () => {
      await queryClient.invalidateQueries({ queryKey: sessionKey }); // the same user answers /auth/me again
    });
    await act(async () => new Promise((resolve) => setTimeout(resolve, 150)));
    expect(box()).toHaveValue('pere');
    expect(screen.getByText('Pereira')).toBeInTheDocument();
    expect(screen.queryByText('Gómez')).not.toBeInTheDocument();
  });

  it('shows the error and the empty states of the new identity, not the previous one\'s', async () => {
    const { queryClient } = await open();
    await screen.findByText('Gómez');
    backend.override('get', UNFILTERED, { status: 500, body: { codigo: 'ERROR_INTERNO', mensaje: 'detalle interno' } }, { once: true });
    await switchIdentity(queryClient);
    expect(await screen.findByRole('alert')).toHaveTextContent('Ocurrió un problema en el servidor');
    expect(screen.queryByText('Gómez')).not.toBeInTheDocument();
    expect(document.body).not.toHaveTextContent('detalle interno');
    await userEvent.setup().click(screen.getByRole('button', { name: 'Reintentar' }));
    expect(await screen.findByText('Gómez')).toBeInTheDocument();
  });

  it('keeps the personal data out of the console while the identity changes', async () => {
    const spies = (['log', 'info', 'warn', 'error', 'debug'] as const).map((level) => vi.spyOn(console, level).mockImplementation(() => undefined));
    try {
      const { queryClient, user } = await open();
      await screen.findByText('Gómez');
      await user.type(box(), 'pere');
      await waitFor(() => expect(listRequests().some((r) => r.url === SEARCHED)).toBe(true));
      await switchIdentity(queryClient);
      await screen.findByText('Gómez');
      expect(spies.flatMap((spy) => spy.mock.calls).flat().join(' ')).not.toMatch(/pere|Pereira|Gómez/);
    } finally {
      spies.forEach((spy) => spy.mockRestore());
    }
  });

  it('a late ERROR of the previous identity never shows an alert on the new identity\'s screen', async () => {
    const { queryClient, user } = await open();
    await screen.findByText('Gómez');
    const late = failLater(SEARCHED);
    await user.type(box(), 'pere');
    await waitFor(() => expect(late.started()).toBe(true)); // the search is in flight
    const fresh = backend.hold('get', UNFILTERED);
    await switchIdentity(queryClient);
    expect(await screen.findByText('Cargando familias…')).toBeInTheDocument();
    late.release();
    await sleep(150);
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    expect(screen.getByText('Cargando familias…')).toBeInTheDocument();
    fresh.release();
    expect(await screen.findByText('3 familias')).toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('an in-flight answer of the previous identity under the SAME query key never overwrites the new identity\'s data', async () => {
    ['Gómez', 'Pereira', 'Pereyra'].forEach((name) => backend.addFamily(name));
    backend.signIn('marcos@example.com');
    const stale = backend.hold('get', UNFILTERED); // computed now: 3 families
    const { queryClient } = renderApp('/admin/familias');
    await screen.findByRole('heading', { name: 'Familias' });
    await waitFor(() => expect(listRequests()).toHaveLength(1));
    backend.addFamily('Secreta'); // only the new identity's answer knows it
    await switchIdentity(queryClient);
    await waitFor(() => expect(screen.getByText('4 familias')).toBeInTheDocument());
    stale.release();
    await sleep(150);
    expect(screen.getByText('4 familias')).toBeInTheDocument();
  });

  it('a term typed but not yet debounced when the administrator changes is gone and never sent', async () => {
    const { queryClient, user } = await open();
    await screen.findByText('Gómez');
    await user.type(box(), 'pere');
    const before = listRequests().length;
    await switchIdentity(queryClient);
    await sleep(600);
    expect(box()).toHaveValue('');
    expect(listRequests().slice(before).some((r) => r.url.includes('busqueda'))).toBe(false);
  });
});
