import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it } from 'vitest';
import type { FakeBackend, FakeInvitationStatus } from '@/test/fakeBackend';
import { createBackend, renderApp } from '@/test/render';

// MOCK backend (see src/test/fakeBackend.ts). Real-backend evidence lives in e2e/invitations.real.spec.ts.
let backend: FakeBackend;

beforeEach(() => {
  localStorage.clear();
  sessionStorage.clear();
  backend = createBackend();
});

const UNAVAILABLE = 'Esta invitación no está disponible';

async function fillRegistration(overrides: Partial<Record<'nombre' | 'apellido' | 'email' | 'password' | 'confirm', string>> = {}) {
  const user = userEvent.setup();
  const values = { nombre: 'Ana', apellido: 'Sosa', email: 'ana@example.com', password: 'una-clave-larga-1', confirm: 'una-clave-larga-1', ...overrides };
  await user.type(await screen.findByLabelText(/^Nombre/), values.nombre);
  await user.type(screen.getByLabelText(/^Apellido/), values.apellido);
  const email = screen.getByLabelText(/^Correo electrónico/);
  await user.clear(email);
  if (values.email) await user.type(email, values.email);
  await user.type(screen.getByLabelText(/^Contraseña/), values.password);
  await user.type(screen.getByLabelText(/^Repetir contraseña/), values.confirm);
  return user;
}

describe('opening the invitation link (mock backend)', () => {
  it('validates a pending invitation, shows the form and prefills the suggested email', async () => {
    const token = backend.seedInvitation({ status: 'PENDIENTE', email: 'sugerido@example.com' });
    renderApp(`/registro/invitacion#${token}`);

    expect(await screen.findByRole('heading', { name: 'Creá tu cuenta' })).toBeInTheDocument();
    expect(screen.getByText(/Te invitaron a Banfield Patín Carrera/)).toBeInTheDocument();
    expect(screen.getByLabelText(/^Correo electrónico/)).toHaveValue('sugerido@example.com');
    expect(screen.queryByRole('combobox')).not.toBeInTheDocument(); // no role selector
    expect(screen.queryByLabelText(/rol/i)).not.toBeInTheDocument();
  });

  it('sends the token in the request body (never in a URL) and removes it from the address', async () => {
    const token = backend.seedInvitation({ status: 'PENDIENTE' });
    renderApp(`/registro/invitacion#${token}`);
    await screen.findByRole('heading', { name: 'Creá tu cuenta' });

    const validation = backend.requests.find((r) => r.url === '/auth/invitaciones/validar');
    expect(validation?.method).toBe('post');
    expect(validation?.csrfHeader).toBeTruthy();
    expect(validation?.body).toEqual({ token });
    expect(backend.requests.every((r) => !r.url.includes(token))).toBe(true);
    await waitFor(() => expect(screen.getByTestId('location').textContent).toBe('/registro/invitacion'));
    expect(document.body.innerHTML).not.toContain(token);
  });

  it('validates only once even though the token leaves the URL', async () => {
    const token = backend.seedInvitation({ status: 'PENDIENTE' });
    renderApp(`/registro/invitacion#${token}`);
    await screen.findByRole('heading', { name: 'Creá tu cuenta' });
    expect(backend.requests.filter((r) => r.url === '/auth/invitaciones/validar')).toHaveLength(1);
  });

  // The backend answers the SAME 400 INVITACION_NO_DISPONIBLE for all of these on purpose, so the UI cannot tell them apart.
  it.each<[string, FakeInvitationStatus | 'UNKNOWN']>([
    ['unknown / invalid', 'UNKNOWN'],
    ['expired', 'EXPIRADA'],
    ['revoked', 'REVOCADA'],
    ['already used', 'USADA'],
  ])('shows the uniform "not available" screen for an %s invitation, without a form', async (_label, status) => {
    const token = status === 'UNKNOWN' ? 'A'.repeat(43) : backend.seedInvitation({ status });
    renderApp(`/registro/invitacion#${token}`);

    expect(await screen.findByRole('heading', { name: UNAVAILABLE })).toBeInTheDocument();
    expect(screen.getByText(/haya vencido, haya sido revocado o ya se haya utilizado/)).toBeInTheDocument();
    expect(screen.getByText(/no podemos decirte cuál de esos casos es/)).toBeInTheDocument();
    expect(screen.queryByLabelText(/^Contraseña/)).not.toBeInTheDocument();
    expect(backend.writes().some((r) => r.url === '/auth/registro/invitacion')).toBe(false);
  });

  it.each(['/registro', '/registro/invitacion'])('explains that an invitation link is needed at %s', async (path) => {
    renderApp(path);
    expect(await screen.findByRole('heading', { name: 'Necesitás un enlace de invitación' })).toBeInTheDocument();
    expect(backend.requests.some((r) => r.url === '/auth/invitaciones/validar')).toBe(false);
  });

  it('offers a retry when validation fails for a connection or server reason (not the same as "not available")', async () => {
    const token = backend.seedInvitation({ status: 'PENDIENTE' });
    backend.override('post', '/auth/invitaciones/validar', { status: 503, body: { codigo: 'SERVICIO_NO_DISPONIBLE', mensaje: 'x' } });
    renderApp(`/registro/invitacion#${token}`);

    expect(await screen.findByRole('heading', { name: 'No pudimos verificar tu invitación' })).toBeInTheDocument();
    expect(screen.getByRole('alert')).toHaveTextContent('El servicio no está disponible');
    await userEvent.setup().click(screen.getByRole('button', { name: 'Reintentar' }));
    expect(await screen.findByRole('heading', { name: 'Creá tu cuenta' })).toBeInTheDocument();
  });

  it('reports a network failure while validating', async () => {
    const token = backend.seedInvitation({ status: 'PENDIENTE' });
    backend.override('get', '/auth/csrf', 'network');
    renderApp(`/registro/invitacion#${token}`);
    expect(await screen.findByRole('alert')).toHaveTextContent('No pudimos conectar con el servidor');
  });
});

describe('legacy links /registro/invitacion/TOKEN (temporary compatibility, mock backend)', () => {
  it('still validates a legacy path link, sends the token only in the body and removes it from the address', async () => {
    const token = backend.seedInvitation({ status: 'PENDIENTE', email: 'legacy@example.com' });
    renderApp(`/registro/invitacion/${token}`);

    expect(await screen.findByRole('heading', { name: 'Creá tu cuenta' })).toBeInTheDocument();
    expect(screen.getByLabelText(/^Correo electrónico/)).toHaveValue('legacy@example.com');
    await waitFor(() => expect(screen.getByTestId('location').textContent).toBe('/registro/invitacion'));
    expect(backend.requests.find((r) => r.url === '/auth/invitaciones/validar')?.body).toEqual({ token });
    expect(backend.requests.every((r) => !r.url.includes(token))).toBe(true);
  });

  it('completes a registration from a legacy link', async () => {
    const token = backend.seedInvitation({ status: 'PENDIENTE' });
    renderApp(`/registro/invitacion/${token}`);
    const user = await fillRegistration();
    await user.click(screen.getByRole('button', { name: 'Crear mi cuenta' }));
    expect(await screen.findByText('Tu cuenta fue creada.')).toBeInTheDocument();
    expect(backend.statusOfToken(token)).toBe('USADA');
  });

  it('keeps the uniform "not available" screen for a used legacy link', async () => {
    const token = backend.seedInvitation({ status: 'USADA' });
    renderApp(`/registro/invitacion/${token}`);
    expect(await screen.findByRole('heading', { name: UNAVAILABLE })).toBeInTheDocument();
  });
});

describe('fragment links /registro/invitacion#TOKEN (H-01, mock backend)', () => {
  it('reads the token from the fragment and strips the fragment from the address before validating', async () => {
    const token = backend.seedInvitation({ status: 'PENDIENTE' });
    renderApp(`/registro/invitacion#${token}`);

    expect(await screen.findByRole('heading', { name: 'Creá tu cuenta' })).toBeInTheDocument();
    expect(screen.getByTestId('location').textContent).toBe('/registro/invitacion');
    const validation = backend.requests.find((r) => r.url === '/auth/invitaciones/validar');
    expect(validation?.body).toEqual({ token });
    expect(backend.requests.every((r) => !r.url.includes(token))).toBe(true);
    expect(localStorage.length + sessionStorage.length).toBe(0);
  });

  it('treats an empty fragment as a missing invitation', async () => {
    renderApp('/registro/invitacion#');
    expect(await screen.findByRole('heading', { name: 'Necesitás un enlace de invitación' })).toBeInTheDocument();
    expect(backend.requests.some((r) => r.url === '/auth/invitaciones/validar')).toBe(false);
  });

  it('keeps the uniform message for an unknown token passed in the fragment', async () => {
    renderApp(`/registro/invitacion#${'B'.repeat(43)}`);
    expect(await screen.findByRole('heading', { name: UNAVAILABLE })).toBeInTheDocument();
  });
});

describe('completing the registration (mock backend)', () => {
  it('registers, sends only the allowed fields, and redirects to /login WITHOUT signing in', async () => {
    const token = backend.seedInvitation({ status: 'PENDIENTE' });
    renderApp(`/registro/invitacion#${token}`);
    const user = await fillRegistration();
    await user.click(screen.getByRole('button', { name: 'Crear mi cuenta' }));

    expect(await screen.findByRole('heading', { name: 'Ingresá a tu portal' })).toBeInTheDocument();
    expect(screen.getByText('Tu cuenta fue creada.')).toBeInTheDocument();
    expect(screen.getByTestId('location')).toHaveTextContent(/^\/login$/);

    const write = backend.writes().find((r) => r.url === '/auth/registro/invitacion');
    expect(write?.body).toEqual({ token, nombre: 'Ana', apellido: 'Sosa', email: 'ana@example.com', password: 'una-clave-larga-1' });
    expect(write?.csrfHeader).toBeTruthy();
    expect(backend.statusOfToken(token)).toBe('USADA');
    expect(backend.writes().some((r) => r.url.includes('/login'))).toBe(false); // no automatic sign-in
    expect(localStorage.length + sessionStorage.length).toBe(0);
  });

  it('lets the new user sign in afterwards with the chosen credentials', async () => {
    const token = backend.seedInvitation({ status: 'PENDIENTE' });
    renderApp(`/registro/invitacion#${token}`);
    const user = await fillRegistration();
    await user.click(screen.getByRole('button', { name: 'Crear mi cuenta' }));

    await user.type(await screen.findByLabelText(/^Correo electrónico/), 'ana@example.com');
    await user.type(screen.getByLabelText(/^Contraseña/), 'una-clave-larga-1');
    await user.click(screen.getByRole('button', { name: 'Ingresar' }));
    expect(await screen.findByRole('heading', { name: 'Hola, Ana' })).toBeInTheDocument();
  });

  it('validates every field on the client before calling the backend', async () => {
    const token = backend.seedInvitation({ status: 'PENDIENTE' });
    renderApp(`/registro/invitacion#${token}`);
    const user = userEvent.setup();
    await user.click(await screen.findByRole('button', { name: 'Crear mi cuenta' }));

    expect(await screen.findByText('Ingresá tu nombre.')).toBeInTheDocument();
    expect(screen.getByText('Ingresá tu apellido.')).toBeInTheDocument();
    expect(screen.getByText('Ingresá tu correo electrónico.')).toBeInTheDocument();
    expect(screen.getByText('Usá al menos 10 caracteres.')).toBeInTheDocument();
    expect(backend.writes().some((r) => r.url === '/auth/registro/invitacion')).toBe(false);
  });

  it('rejects mismatching passwords and short passwords', async () => {
    const token = backend.seedInvitation({ status: 'PENDIENTE' });
    renderApp(`/registro/invitacion#${token}`);
    const user = await fillRegistration({ password: 'una-clave-larga-1', confirm: 'otra-distinta-99' });
    await user.click(screen.getByRole('button', { name: 'Crear mi cuenta' }));
    expect(await screen.findByText('Las contraseñas no coinciden.')).toBeInTheDocument();

    await user.clear(screen.getByLabelText(/^Contraseña/));
    await user.type(screen.getByLabelText(/^Contraseña/), 'corta');
    await user.click(screen.getByRole('button', { name: 'Crear mi cuenta' }));
    expect(await screen.findByText('Usá al menos 10 caracteres.')).toBeInTheDocument();
    expect(backend.writes().some((r) => r.url === '/auth/registro/invitacion')).toBe(false);
  });

  it('rejects an invalid email format', async () => {
    const token = backend.seedInvitation({ status: 'PENDIENTE' });
    renderApp(`/registro/invitacion#${token}`);
    const user = await fillRegistration({ email: 'sin-arroba' });
    await user.click(screen.getByRole('button', { name: 'Crear mi cuenta' }));
    expect(await screen.findByText('Ingresá un correo electrónico válido.')).toBeInTheDocument();
  });

  it('shows 409 EMAIL_YA_REGISTRADO next to the email field and keeps the invitation usable', async () => {
    const token = backend.seedInvitation({ status: 'PENDIENTE' });
    renderApp(`/registro/invitacion#${token}`);
    const user = await fillRegistration({ email: 'lucia@example.com' }); // already exists in the fake
    await user.click(screen.getByRole('button', { name: 'Crear mi cuenta' }));

    expect(await screen.findByText(/Ya existe una cuenta con ese correo/)).toBeInTheDocument();
    expect(screen.getByLabelText(/^Correo electrónico/)).toHaveAttribute('aria-invalid', 'true');
    expect(backend.statusOfToken(token)).toBe('PENDIENTE');

    const email = screen.getByLabelText(/^Correo electrónico/);
    await user.clear(email);
    await user.type(email, 'otra@example.com');
    await user.click(screen.getByRole('button', { name: 'Crear mi cuenta' }));
    expect(await screen.findByRole('heading', { name: 'Ingresá a tu portal' })).toBeInTheDocument();
  });

  it('maps backend field validation errors to the field', async () => {
    const token = backend.seedInvitation({ status: 'PENDIENTE' });
    renderApp(`/registro/invitacion#${token}`);
    const user = await fillRegistration();
    backend.override('post', '/auth/registro/invitacion', {
      status: 400,
      body: { codigo: 'VALIDACION', mensaje: 'Datos inválidos.', detalles: [{ campo: 'password', mensaje: 'La contraseña no cumple la política.' }] },
    });
    await user.click(screen.getByRole('button', { name: 'Crear mi cuenta' }));

    expect(await screen.findByText('La contraseña no cumple la política.')).toBeInTheDocument();
    expect(screen.getByRole('alert')).toHaveTextContent('Revisá los datos marcados');
  });

  it('switches to the "not available" screen if the invitation is consumed or revoked before submitting', async () => {
    const token = backend.seedInvitation({ status: 'PENDIENTE' });
    renderApp(`/registro/invitacion#${token}`);
    const user = await fillRegistration();
    backend.override('post', '/auth/registro/invitacion', {
      status: 400,
      body: { codigo: 'INVITACION_NO_DISPONIBLE', mensaje: 'La invitación no está disponible.' },
    });
    await user.click(screen.getByRole('button', { name: 'Crear mi cuenta' }));
    expect(await screen.findByRole('heading', { name: UNAVAILABLE })).toBeInTheDocument();
  });

  it('shows server and network failures without losing what was typed, and never retries the write', async () => {
    const token = backend.seedInvitation({ status: 'PENDIENTE' });
    renderApp(`/registro/invitacion#${token}`);
    const user = await fillRegistration();
    backend.override('post', '/auth/registro/invitacion', { status: 503, body: { codigo: 'SERVICIO_NO_DISPONIBLE', mensaje: 'x' } });
    await user.click(screen.getByRole('button', { name: 'Crear mi cuenta' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('El servicio no está disponible');
    expect(screen.getByLabelText(/^Nombre/)).toHaveValue('Ana');
    expect(backend.writes().filter((r) => r.url === '/auth/registro/invitacion')).toHaveLength(1);
  });

  it('cannot be opened a second time once used', async () => {
    const token = backend.seedInvitation({ status: 'PENDIENTE' });
    const first = renderApp(`/registro/invitacion#${token}`);
    const user = await fillRegistration();
    await user.click(screen.getByRole('button', { name: 'Crear mi cuenta' }));
    await screen.findByRole('heading', { name: 'Ingresá a tu portal' });
    first.queryClient.clear();

    document.body.innerHTML = '';
    renderApp(`/registro/invitacion#${token}`);
    expect(await screen.findByRole('heading', { name: UNAVAILABLE })).toBeInTheDocument();
  });
});
