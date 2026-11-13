import { act, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { AppProviders, createQueryClient } from '@/app/providers';
import type { FakeBackend } from '@/test/fakeBackend';
import { createBackend, renderApp } from '@/test/render';
import { sessionKey } from './session';

// H-04 (MOCK backend). Real-backend evidence lives in the Playwright specs.
let backend: FakeBackend;

beforeEach(() => {
  localStorage.clear();
  sessionStorage.clear();
  backend = createBackend();
});

afterEach(() => backend.clearOverrides());

const LIST = '/admin/invitaciones?page=0&size=10';
const meCalls = () => backend.requests.filter((r) => r.url === '/auth/me').length;

describe('401 without a code on authenticated requests', () => {
  it('re-checks the session and goes to login when the server confirms it is gone', async () => {
    backend.signIn('marcos@example.com');
    backend.override('get', LIST, { status: 401, body: '<html>proxy</html>' });
    renderApp('/admin/invitaciones');
    backend.expireSession(); // the server really dropped it

    expect(await screen.findByRole('heading', { name: 'Acceso administrativo' })).toBeInTheDocument();
  });

  it('keeps the session when the re-check answers 503 (state unknown, never close it)', async () => {
    backend.signIn('marcos@example.com');
    renderApp('/admin/invitaciones');
    await screen.findByRole('heading', { name: 'Invitaciones' });
    const before = meCalls();
    backend.override('get', `${LIST}&estado=USADA`, { status: 401 });
    backend.override('get', '/auth/me', { status: 503, body: { codigo: 'SERVICIO_NO_DISPONIBLE', mensaje: 'x' } });
    await userEvent.setup().selectOptions(screen.getByLabelText('Estado'), 'USADA');

    await waitFor(() => expect(meCalls()).toBeGreaterThan(before));
    expect(await screen.findByText(/Mostramos la última información conocida/)).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Invitaciones' })).toBeInTheDocument();
  });

  it('keeps the session when the re-check succeeds (the 401 was not about the session)', async () => {
    backend.signIn('marcos@example.com');
    renderApp('/admin/invitaciones');
    await screen.findByRole('heading', { name: 'Invitaciones' });
    backend.override('get', `${LIST}&estado=USADA`, { status: 401 });
    await userEvent.setup().selectOptions(screen.getByLabelText('Estado'), 'USADA');

    await waitFor(() => expect(meCalls()).toBeGreaterThan(1));
    expect(screen.getByRole('heading', { name: 'Invitaciones' })).toBeInTheDocument();
  });

  it('does not touch the session for 503 or network errors on authenticated requests', async () => {
    backend.signIn('marcos@example.com');
    backend.override('get', LIST, { status: 503, body: { codigo: 'SERVICIO_NO_DISPONIBLE', mensaje: 'x' } });
    renderApp('/admin/invitaciones');
    expect(await screen.findByRole('alert')).toHaveTextContent('El servicio no está disponible');
    backend.override('get', LIST, 'network');
    await userEvent.setup().click(screen.getByRole('button', { name: 'Reintentar' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('No pudimos conectar');
    expect(meCalls()).toBe(1);
    expect(screen.getByRole('heading', { name: 'Invitaciones' })).toBeInTheDocument();
  });

  it('does not re-check the session for expected 401s (wrong password, wrong TOTP code)', async () => {
    renderApp('/login');
    const user = userEvent.setup();
    await user.type(await screen.findByLabelText(/^Correo electrónico/), 'lucia@example.com');
    await user.type(screen.getByLabelText(/^Contraseña/), 'wrong-password');
    await user.click(screen.getByRole('button', { name: 'Ingresar' }));
    await screen.findByRole('alert');
    expect(meCalls()).toBe(1);
  });

  it('does not end a session because a PUBLIC endpoint answered 401', async () => {
    backend.signIn('lucia@example.com');
    const token = backend.seedInvitation({ status: 'PENDIENTE' });
    backend.override('post', '/auth/invitaciones/validar', { status: 401 });
    const { queryClient } = renderApp(`/registro/invitacion#${token}`);
    await screen.findByRole('heading', { name: 'No pudimos verificar tu invitación' });
    // No session event at all: the cached session was neither dropped nor re-checked.
    expect(queryClient.getQueryState(sessionKey)).toBeUndefined();
    expect(meCalls()).toBe(0);
  });
});

describe('private state is cleared when the identity changes', () => {
  it('drops cached private data when a different identity signs in', async () => {
    backend.signIn('marcos@example.com', true); // pending admin: the family login form is shown
    const { queryClient } = renderApp('/login');
    queryClient.setQueryData(['admin', 'invitations', { status: 'ALL', page: 0 }], { contenido: [{ email: 'privado@example.com' }] });
    const user = userEvent.setup();
    await user.type(await screen.findByLabelText(/^Correo electrónico/), 'lucia@example.com');
    await user.type(screen.getByLabelText(/^Contraseña/), 'correct-horse');
    await user.click(screen.getByRole('button', { name: 'Ingresar' }));

    await screen.findByRole('heading', { name: 'Hola, Lucía' });
    expect(queryClient.getQueryData(['admin', 'invitations', { status: 'ALL', page: 0 }])).toBeUndefined();
    // Nothing of the previous identity survives; only the session and the new family's own queries (the dashboard loads them).
    const keys = queryClient.getQueryCache().getAll().map((q) => q.queryKey[0]);
    expect(keys).toContain('session');
    expect(keys.every((key) => key === 'session' || key === 'familia')).toBe(true);
  });

  it('drops cached data when the second factor upgrades the session', async () => {
    backend.signIn('marcos@example.com', true);
    const { queryClient } = renderApp('/admin/mfa');
    queryClient.setQueryData(['leftover'], 'x');
    const user = userEvent.setup();
    await user.type(await screen.findByLabelText(/^Código de 6 dígitos/), '123456');
    await user.click(screen.getByRole('button', { name: 'Verificar e ingresar' }));
    await screen.findByRole('heading', { name: 'Hola, Marcos' });
    expect(queryClient.getQueryData(['leftover'])).toBeUndefined();
  });
});

describe('logout is synchronised across tabs', () => {
  it('a logout in one tab clears the session and private data in another tab, with a payload free of data', async () => {
    backend.signIn('marcos@example.com');
    const otherTab = createQueryClient();
    otherTab.setQueryData(sessionKey, { id: 'u-admin', rol: 'ADMIN' });
    otherTab.setQueryData(['admin', 'invitations'], { contenido: [{ email: 'privado@example.com' }] });
    const received: unknown[] = [];
    const spy = new BroadcastChannel('bp-session');
    spy.onmessage = (event: MessageEvent) => received.push(event.data);
    render(
      <AppProviders client={otherTab}>
        <span />
      </AppProviders>,
    );

    renderApp('/admin');
    await userEvent.setup().click(await screen.findByRole('button', { name: 'Cerrar sesión' }));
    await screen.findByRole('heading', { name: 'Acceso administrativo' });

    await waitFor(() => expect(otherTab.getQueryData(sessionKey)).toBeNull());
    expect(otherTab.getQueryData(['admin', 'invitations'])).toBeUndefined();
    expect(received).toEqual([{ type: 'logout' }]);
    spy.close();
  });

  it('a login in another tab makes this tab re-check its session', async () => {
    const thisTab = createQueryClient();
    thisTab.setQueryData(sessionKey, null);
    render(
      <AppProviders client={thisTab}>
        <span />
      </AppProviders>,
    );
    renderApp('/login');
    const user = userEvent.setup();
    await user.type(await screen.findByLabelText(/^Correo electrónico/), 'lucia@example.com');
    await user.type(screen.getByLabelText(/^Contraseña/), 'correct-horse');
    await act(async () => {
      await user.click(screen.getByRole('button', { name: 'Ingresar' }));
    });
    await waitFor(() => expect(thisTab.getQueryState(sessionKey)?.isInvalidated ?? false).toBe(true));
  });
});
