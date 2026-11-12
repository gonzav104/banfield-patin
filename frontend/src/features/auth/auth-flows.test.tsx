import { act, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { admin, createBackend, family, newAdmin, renderApp } from '@/test/render';
import { FAKE_SECRET, VALID_TOTP, type FakeBackend } from '@/test/fakeBackend';
import { http } from '@/lib/http';
import { sessionKey } from './session';

// jsdom has no canvas: the QR rendering is replaced by a stub image; the secret itself is asserted separately.
vi.mock('qrcode', () => ({ default: { toDataURL: () => Promise.resolve('data:image/png;base64,AAAA') } }));

let backend: FakeBackend;

beforeEach(() => {
  localStorage.clear();
  sessionStorage.clear();
  backend = createBackend();
});

async function fillCredentials(email: string, password: string) {
  const user = userEvent.setup();
  await user.type(await screen.findByLabelText(/^Correo electrónico/), email);
  await user.type(screen.getByLabelText(/^Contraseña/), password);
  return user;
}

function expectNothingSensitiveInBrowserStorage() {
  const dump = JSON.stringify({ ...localStorage }) + JSON.stringify({ ...sessionStorage });
  expect(dump).not.toMatch(/eyJ|token|jwt|secret|password|correct-horse|admin-pass/i);
  expect(localStorage.length + sessionStorage.length).toBe(0);
}

describe('family login (mock backend)', () => {
  it('sends anonymous visitors of /familia to /login', async () => {
    renderApp('/familia');
    expect(await screen.findByRole('heading', { name: 'Ingresá a tu portal' })).toBeInTheDocument();
    expect(screen.getByText('Ingresá para acceder a esa sección.')).toBeInTheDocument();
  });

  it('validates the form before calling the backend', async () => {
    renderApp('/login');
    const user = userEvent.setup();
    await user.click(await screen.findByRole('button', { name: 'Ingresar' }));

    expect(await screen.findByText('Ingresá tu correo electrónico.')).toBeInTheDocument();
    expect(screen.getByText('Ingresá tu contraseña.')).toBeInTheDocument();
    expect(backend.writes()).toHaveLength(0);

    await user.type(screen.getByLabelText(/^Correo electrónico/), 'not-an-email');
    await user.click(screen.getByRole('button', { name: 'Ingresar' }));
    expect(await screen.findByText('Ingresá un correo electrónico válido.')).toBeInTheDocument();
    expect(backend.writes()).toHaveLength(0);
  });

  it('shows a generic error for wrong credentials and stays on /login', async () => {
    renderApp('/login');
    const user = await fillCredentials('lucia@example.com', 'wrong-password');
    await user.click(screen.getByRole('button', { name: 'Ingresar' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('El correo o la contraseña no son correctos');
    expect(screen.getByRole('heading', { name: 'Ingresá a tu portal' })).toBeInTheDocument();
    expect(screen.queryByLabelText(/^Contraseña/)).toHaveValue('wrong-password');
  });

  it('rejects an administrator who tries the family login', async () => {
    renderApp('/login');
    const user = await fillCredentials('marcos@example.com', 'admin-pass-123');
    await user.click(screen.getByRole('button', { name: 'Ingresar' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('no son correctos');
  });

  it('logs in, lands on /familia with the real identity and stores nothing in browser storage', async () => {
    renderApp('/login');
    const user = await fillCredentials('lucia@example.com', 'correct-horse');
    await user.click(screen.getByRole('button', { name: 'Ingresar' }));

    expect(await screen.findByRole('heading', { name: 'Hola, Lucía' })).toBeInTheDocument();
    expect(screen.getAllByText('lucia@example.com').length).toBeGreaterThan(0);
    expectNothingSensitiveInBrowserStorage();
    // The login write carried a CSRF header that matched the cookie at that moment.
    expect(backend.writes()[0]).toMatchObject({ url: '/auth/login' });
    expect(backend.writes()[0]?.csrfHeader).toBeTruthy();
  });

  it('does not keep credentials in the mutation cache after a login', async () => {
    const { queryClient } = renderApp('/login');
    const user = await fillCredentials('lucia@example.com', 'correct-horse');
    await user.click(screen.getByRole('button', { name: 'Ingresar' }));
    await screen.findByRole('heading', { name: 'Hola, Lucía' });

    await waitFor(() => {
      const retained = JSON.stringify(queryClient.getMutationCache().getAll().map((m) => m.state.variables));
      expect(retained).not.toContain('correct-horse');
    });
  });

  it('restores the session after a reload through /api/auth/me', async () => {
    backend.signIn('lucia@example.com');
    renderApp('/familia');

    expect(await screen.findByRole('heading', { name: 'Hola, Lucía' })).toBeInTheDocument();
    expect(backend.requests.some((r) => r.url === '/auth/me')).toBe(true);
  });

  it('redirects a signed-in family user away from /login', async () => {
    backend.signIn('lucia@example.com');
    renderApp('/login');
    expect(await screen.findByRole('heading', { name: 'Hola, Lucía' })).toBeInTheDocument();
  });

  it('logs out for real: server call with fresh CSRF, local state cleared, protected route closed again', async () => {
    backend.signIn('lucia@example.com');
    renderApp('/familia');
    const user = userEvent.setup();
    await user.click(await screen.findByRole('button', { name: 'Cerrar sesión' }));

    expect(await screen.findByRole('heading', { name: 'Ingresá a tu portal' })).toBeInTheDocument();
    const logout = backend.writes().find((r) => r.url === '/auth/logout');
    expect(logout?.csrfHeader).toBeTruthy();
    expect(backend.requests.some((r) => r.url === '/auth/csrf')).toBe(true);

    // The session is really gone on the server, not just hidden in the UI.
    await expect(http.get('/auth/me')).rejects.toMatchObject({ kind: 'unauthenticated' });
    expect(screen.queryByRole('heading', { name: 'Hola, Lucía' })).not.toBeInTheDocument();
  });

  it('keeps the user signed in and reports the problem when logout fails', async () => {
    backend.signIn('lucia@example.com');
    renderApp('/familia');
    const user = userEvent.setup();
    backend.override('post', '/auth/logout', { status: 503, body: { codigo: 'SERVICIO_NO_DISPONIBLE', mensaje: 'x' } });
    await user.click(await screen.findByRole('button', { name: 'Cerrar sesión' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('no está disponible');
    expect(screen.getByRole('heading', { name: 'Hola, Lucía' })).toBeInTheDocument();
  });

  it('shows a permission screen (not the admin panel) when a family user opens /admin', async () => {
    backend.signIn('lucia@example.com');
    renderApp('/admin');

    expect(await screen.findByRole('heading', { name: 'No tenés permiso para ver esta sección' })).toBeInTheDocument();
    expect(screen.queryByText('Panel administrativo')).not.toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Volver a mi inicio' })).toHaveAttribute('href', '/familia');
  });

  it('reports a network failure on login without losing what the user typed', async () => {
    renderApp('/login');
    const user = await fillCredentials('lucia@example.com', 'correct-horse');
    backend.override('get', '/auth/csrf', 'network');
    await user.click(screen.getByRole('button', { name: 'Ingresar' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('No pudimos conectar con el servidor');
    expect(screen.getByLabelText(/^Correo electrónico/)).toHaveValue('lucia@example.com');
  });

  it('shows the service-unavailable screen on 503 from /me without closing anything, and recovers on retry', async () => {
    backend.signIn('lucia@example.com');
    backend.override('get', '/auth/me', { status: 503, body: { codigo: 'SERVICIO_NO_DISPONIBLE', mensaje: 'x' } });
    renderApp('/familia');

    expect(await screen.findByRole('heading', { name: 'Servicio no disponible' })).toBeInTheDocument();
    expect(screen.getByText(/Tu sesión no se cerró/)).toBeInTheDocument();

    await userEvent.setup().click(screen.getByRole('button', { name: 'Reintentar' }));
    expect(await screen.findByRole('heading', { name: 'Hola, Lucía' })).toBeInTheDocument();
  });

  it('keeps the known identity (with a warning) when a later refresh gets 503', async () => {
    backend.signIn('lucia@example.com');
    const { queryClient } = renderApp('/familia');
    await screen.findByRole('heading', { name: 'Hola, Lucía' });

    backend.override('get', '/auth/me', { status: 503, body: { codigo: 'SERVICIO_NO_DISPONIBLE', mensaje: 'x' } });
    await act(async () => {
      await queryClient.invalidateQueries({ queryKey: sessionKey });
    });

    expect(await screen.findByText(/Mostramos la última información conocida/)).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Hola, Lucía' })).toBeInTheDocument();
  });

  it('drops to the login screen when any request reports the session is gone (401)', async () => {
    backend.signIn('lucia@example.com');
    const { queryClient } = renderApp('/familia');
    await screen.findByRole('heading', { name: 'Hola, Lucía' });

    backend.expireSession();
    await act(async () => {
      await queryClient.invalidateQueries({ queryKey: sessionKey });
    });

    expect(await screen.findByRole('heading', { name: 'Ingresá a tu portal' })).toBeInTheDocument();
  });

  it('offers no sign-up, no role choice and no link to the admin login', async () => {
    renderApp('/login');
    await screen.findByRole('heading', { name: 'Ingresá a tu portal' });

    expect(screen.queryByRole('link')).not.toBeInTheDocument();
    expect(screen.queryByText(/regist/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/admin/i)).not.toBeInTheDocument();
    expect(screen.queryByRole('combobox')).not.toBeInTheDocument();
    expect(screen.queryByRole('radio')).not.toBeInTheDocument();
  });
});

describe('admin login and MFA (mock backend)', () => {
  it('sends anonymous visitors of /admin to /admin/login', async () => {
    renderApp('/admin');
    expect(await screen.findByRole('heading', { name: 'Acceso administrativo' })).toBeInTheDocument();
  });

  it('a valid password alone leads to the second factor, never to the panel', async () => {
    renderApp('/admin/login');
    const user = await fillCredentials('marcos@example.com', 'admin-pass-123');
    await user.click(screen.getByRole('button', { name: 'Continuar' }));

    expect(await screen.findByRole('heading', { name: 'Verificación en dos pasos' })).toBeInTheDocument();
    expect(screen.queryByText('Panel administrativo')).not.toBeInTheDocument();
  });

  it('keeps a password-only admin session out of /admin and /familia', async () => {
    backend.signIn('marcos@example.com', true);
    renderApp('/admin');
    expect(await screen.findByRole('heading', { name: 'Verificación en dos pasos' })).toBeInTheDocument();
    expect(screen.queryByText('Panel administrativo')).not.toBeInTheDocument();
  });

  it('sends a password-only admin session that opens /familia to the second factor too', async () => {
    backend.signIn('marcos@example.com', true);
    renderApp('/familia');
    expect(await screen.findByRole('heading', { name: 'Verificación en dos pasos' })).toBeInTheDocument();
  });

  it('verifies an enrolled administrator with the TOTP code and opens the panel', async () => {
    backend.signIn('marcos@example.com', true);
    renderApp('/admin/mfa');
    const user = userEvent.setup();
    await user.type(await screen.findByLabelText(/^Código de 6 dígitos/), VALID_TOTP);
    await user.click(screen.getByRole('button', { name: 'Verificar e ingresar' }));

    expect(await screen.findByRole('heading', { name: 'Hola, Marcos' })).toBeInTheDocument();
    expect(screen.getByText('Activa')).toBeInTheDocument();
    const verify = backend.writes().find((r) => r.url === '/auth/admin/mfa/verificar');
    expect(verify?.body).toEqual({ codigo: VALID_TOTP });
    expect(verify?.csrfHeader).toBeTruthy();
    expectNothingSensitiveInBrowserStorage();
  });

  it('rejects a wrong code, keeps the pending session and clears the field', async () => {
    backend.signIn('marcos@example.com', true);
    renderApp('/admin/mfa');
    const user = userEvent.setup();
    await user.type(await screen.findByLabelText(/^Código de 6 dígitos/), '000000');
    await user.click(screen.getByRole('button', { name: 'Verificar e ingresar' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('El código no es correcto o ya fue usado');
    expect(screen.getByRole('heading', { name: 'Verificación en dos pasos' })).toBeInTheDocument();
    await waitFor(() => expect(screen.getByLabelText(/^Código de 6 dígitos/)).toHaveValue(''));
  });

  it('validates the code format before calling the backend', async () => {
    backend.signIn('marcos@example.com', true);
    renderApp('/admin/mfa');
    const user = userEvent.setup();
    await user.type(await screen.findByLabelText(/^Código de 6 dígitos/), '12ab');
    await user.click(screen.getByRole('button', { name: 'Verificar e ingresar' }));

    expect(await screen.findByText('Ingresá los 6 dígitos del código.')).toBeInTheDocument();
    expect(backend.writes()).toHaveLength(0);
  });

  it('enrols a new administrator: shows the secret once, confirms the code, opens the panel', async () => {
    renderApp('/admin/login');
    const user = await fillCredentials('nuevo@example.com', 'admin-pass-123');
    await user.click(screen.getByRole('button', { name: 'Continuar' }));

    expect(await screen.findByRole('heading', { name: 'Configurá la verificación en dos pasos' })).toBeInTheDocument();
    expect(screen.queryByTestId('mfa-secret')).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Empezar configuración' }));

    const secret = await screen.findByTestId('mfa-secret');
    expect(secret.textContent?.replace(/\s/g, '')).toBe(FAKE_SECRET);
    expect(await screen.findByRole('img', { name: /Código QR/ })).toBeInTheDocument();

    await user.type(screen.getByLabelText(/^Código de 6 dígitos/), VALID_TOTP);
    await user.click(screen.getByRole('button', { name: 'Confirmar y activar' }));

    expect(await screen.findByRole('heading', { name: 'Hola, Marcos' })).toBeInTheDocument();
    expect(screen.queryByTestId('mfa-secret')).not.toBeInTheDocument();
    const urls = backend.writes().map((r) => r.url);
    expect(urls).toEqual(['/auth/admin/login', '/auth/admin/mfa/enrolar', '/auth/admin/mfa/confirmar']);
    expect(backend.writes().every((r) => !!r.csrfHeader)).toBe(true);
    expectNothingSensitiveInBrowserStorage();
  });

  it('uses a fresh CSRF token for each step after the session changes', async () => {
    renderApp('/admin/login');
    const user = await fillCredentials('marcos@example.com', 'admin-pass-123');
    await user.click(screen.getByRole('button', { name: 'Continuar' }));
    await user.type(await screen.findByLabelText(/^Código de 6 dígitos/), VALID_TOTP);
    await user.click(screen.getByRole('button', { name: 'Verificar e ingresar' }));
    await screen.findByRole('heading', { name: 'Hola, Marcos' });
    await user.click(screen.getByRole('button', { name: 'Cerrar sesión' }));
    await screen.findByRole('heading', { name: 'Acceso administrativo' });

    const tokens = backend.writes().map((r) => r.csrfHeader);
    expect(tokens).toHaveLength(3);
    expect(new Set(tokens).size).toBe(3);
    expect(backend.requests.filter((r) => r.url === '/auth/csrf')).toHaveLength(3);
  });

  it('sends the user back to /admin/login with a notice when the pending session expires', async () => {
    backend.signIn('marcos@example.com', true);
    renderApp('/admin/mfa');
    const user = userEvent.setup();
    await user.type(await screen.findByLabelText(/^Código de 6 dígitos/), VALID_TOTP);
    backend.expireSession();
    await user.click(screen.getByRole('button', { name: 'Verificar e ingresar' }));

    expect(await screen.findByRole('heading', { name: 'Acceso administrativo' })).toBeInTheDocument();
    expect(screen.getByText('Tu sesión venció. Ingresá de nuevo para continuar.')).toBeInTheDocument();
  });

  it('lets a pending administrator switch account (real logout)', async () => {
    backend.signIn('marcos@example.com', true);
    renderApp('/admin/mfa');
    await userEvent.setup().click(await screen.findByRole('button', { name: 'Ingresar con otra cuenta' }));

    expect(await screen.findByRole('heading', { name: 'Acceso administrativo' })).toBeInTheDocument();
    expect(backend.writes().some((r) => r.url === '/auth/logout')).toBe(true);
  });

  it('shows the admin identity, role and logs out to /admin/login', async () => {
    backend.signIn('marcos@example.com');
    renderApp('/admin');

    expect(await screen.findByRole('heading', { name: 'Hola, Marcos' })).toBeInTheDocument();
    const nav = screen.getByRole('navigation', { name: 'Principal' });
    expect(within(nav).getByRole('link', { name: 'Inicio' })).toBeInTheDocument();
    expect(screen.getAllByText('Administrador').length).toBeGreaterThan(0);

    await userEvent.setup().click(screen.getByRole('button', { name: 'Cerrar sesión' }));
    expect(await screen.findByRole('heading', { name: 'Acceso administrativo' })).toBeInTheDocument();
  });

  it('shows a permission screen when a full admin session opens /familia', async () => {
    backend.signIn('marcos@example.com');
    renderApp('/familia');
    expect(await screen.findByRole('heading', { name: 'No tenés permiso para ver esta sección' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Volver a mi inicio' })).toHaveAttribute('href', '/admin');
  });

  it('shows 503 and network failures on admin login with their own messages', async () => {
    renderApp('/admin/login');
    const user = await fillCredentials('marcos@example.com', 'admin-pass-123');
    backend.override('post', '/auth/admin/login', { status: 503, body: { codigo: 'SERVICIO_NO_DISPONIBLE', mensaje: 'x' } });
    await user.click(screen.getByRole('button', { name: 'Continuar' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('El servicio no está disponible');

    backend.override('get', '/auth/csrf', 'network');
    await user.click(screen.getByRole('button', { name: 'Continuar' }));
    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('No pudimos conectar con el servidor'));
  });
});

describe('unknown routes', () => {
  it('shows a not-found screen', async () => {
    renderApp('/does-not-exist');
    expect(await screen.findByRole('heading', { name: 'Página no encontrada' })).toBeInTheDocument();
  });

  it('sends / to the family login', async () => {
    renderApp('/');
    expect(await screen.findByRole('heading', { name: 'Ingresá a tu portal' })).toBeInTheDocument();
  });
});

// Exported only to keep the shared fixtures referenced from one place.
export const fixtures = { family, admin, newAdmin };
