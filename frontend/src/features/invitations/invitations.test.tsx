import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it } from 'vitest';
import type { FakeBackend } from '@/test/fakeBackend';
import { createBackend, renderApp } from '@/test/render';

// MOCK backend (see src/test/fakeBackend.ts). Real-backend evidence lives in e2e/invitations.real.spec.ts.
let backend: FakeBackend;

beforeEach(() => {
  localStorage.clear();
  sessionStorage.clear();
  backend = createBackend();
});

async function openInvitations() {
  backend.signIn('marcos@example.com');
  renderApp('/admin/invitaciones');
  return screen.findByRole('heading', { name: 'Invitaciones' });
}

describe('admin access to invitations (mock backend)', () => {
  it('sends anonymous visitors to the admin login', async () => {
    renderApp('/admin/invitaciones');
    expect(await screen.findByRole('heading', { name: 'Acceso administrativo' })).toBeInTheDocument();
  });

  it('keeps a family user out of the screen', async () => {
    backend.signIn('lucia@example.com');
    renderApp('/admin/invitaciones');
    expect(await screen.findByRole('heading', { name: 'No tenés permiso para ver esta sección' })).toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: 'Invitaciones' })).not.toBeInTheDocument();
    expect(backend.requests.some((r) => r.url.startsWith('/admin/invitaciones'))).toBe(false);
  });

  it('keeps a password-only admin session out and sends it to the second factor', async () => {
    backend.signIn('marcos@example.com', true);
    renderApp('/admin/invitaciones');
    expect(await screen.findByRole('heading', { name: 'Verificación en dos pasos' })).toBeInTheDocument();
    expect(backend.requests.some((r) => r.url.startsWith('/admin/invitaciones'))).toBe(false);
  });

  it('is reachable from the admin navigation', async () => {
    backend.signIn('marcos@example.com');
    renderApp('/admin');
    await userEvent.setup().click(await screen.findByRole('link', { name: 'Invitaciones' }));
    expect(await screen.findByRole('heading', { name: 'Invitaciones' })).toBeInTheDocument();
  });

  it('shows the server permission error as a message when the API answers 403', async () => {
    backend.signIn('marcos@example.com');
    backend.override('get', '/admin/invitaciones?page=0&size=10', { status: 403, body: { codigo: 'ACCESO_DENEGADO', mensaje: 'x' } });
    renderApp('/admin/invitaciones');
    expect(await screen.findByRole('alert')).toHaveTextContent('No tenés permiso para ver las invitaciones.');
  });
});

describe('invitation list (mock backend)', () => {
  it('shows an empty state with a call to action', async () => {
    await openInvitations();
    expect(await screen.findByText(/Todavía no creaste invitaciones/)).toBeInTheDocument();
  });

  it('shows a loading state, then every status with a text label', async () => {
    backend.seedInvitation({ status: 'PENDIENTE', family: 'Pérez' });
    backend.seedInvitation({ status: 'USADA', family: 'Gómez' });
    backend.seedInvitation({ status: 'REVOCADA', family: 'Díaz' });
    backend.seedInvitation({ status: 'EXPIRADA', family: 'Ruiz' });
    await openInvitations();
    expect(screen.getByText('Cargando invitaciones…')).toBeInTheDocument();

    const list = await screen.findByRole('list');
    for (const [family, label] of [['Pérez', 'Pendiente'], ['Gómez', 'Usada'], ['Díaz', 'Revocada'], ['Ruiz', 'Vencida']]) {
      const row = within(list).getByText(family!).closest('li')!;
      expect(within(row).getByText(label!)).toBeInTheDocument();
    }
  });

  it('filters by status through the API', async () => {
    backend.seedInvitation({ status: 'PENDIENTE', family: 'Pérez' });
    backend.seedInvitation({ status: 'USADA', family: 'Gómez' });
    await openInvitations();
    await screen.findByText('Gómez');

    await userEvent.setup().selectOptions(screen.getByLabelText('Estado'), 'USADA');
    await waitFor(() => expect(screen.queryByText('Pérez')).not.toBeInTheDocument());
    expect(screen.getByText('Gómez')).toBeInTheDocument();
    expect(backend.requests.some((r) => r.url.includes('estado=USADA'))).toBe(true);
  });

  it('says so when a filter has no results', async () => {
    backend.seedInvitation({ status: 'PENDIENTE', family: 'Pérez' });
    await openInvitations();
    await screen.findByText('Pérez');
    await userEvent.setup().selectOptions(screen.getByLabelText('Estado'), 'REVOCADA');
    expect(await screen.findByText('No hay invitaciones con ese estado.')).toBeInTheDocument();
  });

  it('paginates', async () => {
    for (let i = 1; i <= 12; i += 1) backend.seedInvitation({ status: 'PENDIENTE', family: `Familia ${String(i).padStart(2, '0')}` });
    await openInvitations();
    expect(await screen.findByText(/Página 1 de 2 · 12 en total/)).toBeInTheDocument();
    expect(screen.getAllByRole('listitem')).toHaveLength(10);

    await userEvent.setup().click(screen.getByRole('button', { name: 'Siguiente' }));
    expect(await screen.findByText(/Página 2 de 2/)).toBeInTheDocument();
    await waitFor(() => expect(screen.getAllByRole('listitem')).toHaveLength(2));
    expect(screen.getByRole('button', { name: 'Siguiente' })).toBeDisabled();
  });

  it('shows an error with retry when the list cannot be loaded, and recovers', async () => {
    backend.signIn('marcos@example.com');
    backend.override('get', '/admin/invitaciones?page=0&size=10', { status: 503, body: { codigo: 'SERVICIO_NO_DISPONIBLE', mensaje: 'x' } });
    renderApp('/admin/invitaciones');
    expect(await screen.findByRole('alert')).toHaveTextContent('El servicio no está disponible');

    await userEvent.setup().click(screen.getByRole('button', { name: 'Reintentar' }));
    expect(await screen.findByText(/Todavía no creaste invitaciones/)).toBeInTheDocument();
  });
});

describe('create invitation (mock backend)', () => {
  it('creates an invitation for a NEW family and shows the link once, with a copy button', async () => {
    await openInvitations();
    const user = userEvent.setup();
    await user.click(await screen.findByRole('button', { name: 'Nueva invitación' }));
    await user.type(screen.getByLabelText(/^Nombre de referencia de la familia/), 'Familia Fernández');
    await user.type(screen.getByLabelText(/^Correo sugerido/), 'fer@example.com');
    await user.type(screen.getByLabelText(/^Vigencia en días/), '3');
    await user.click(screen.getByRole('button', { name: 'Crear invitación' }));

    const link = await screen.findByLabelText<HTMLInputElement>('Enlace de registro');
    expect(link.value).toMatch(/^http:\/\/app\.test\/registro\/invitacion\/tok-/);
    expect(screen.getByText(/se muestra una sola vez/)).toBeInTheDocument();
    expect(screen.getByText('Familia Fernández', { selector: 'strong' })).toBeInTheDocument();

    const write = backend.writes().find((r) => r.url === '/admin/invitaciones');
    expect(write?.body).toEqual({ nuevaFamilia: { nombreReferencia: 'Familia Fernández' }, emailSugerido: 'fer@example.com', diasVigencia: 3 });
    expect(write?.csrfHeader).toBeTruthy();

    await user.click(screen.getByRole('button', { name: 'Copiar enlace' }));
    expect(await screen.findByText('Enlace copiado al portapapeles.')).toBeInTheDocument();
    expect(await navigator.clipboard.readText()).toBe(link.value);

    // Dismissing drops the token for good: there is no way to show it again, and nothing is stored.
    await user.click(screen.getByRole('button', { name: 'Listo, ya lo copié' }));
    expect(screen.queryByLabelText('Enlace de registro')).not.toBeInTheDocument();
    expect(document.body.innerHTML).not.toContain('tok-');
    expect(localStorage.length + sessionStorage.length).toBe(0);
    expect(await screen.findByText('Familia Fernández')).toBeInTheDocument(); // now listed, without any link
    expect(screen.queryByRole('button', { name: /Copiar/ })).not.toBeInTheDocument();
  });

  it('creates an invitation for an EXISTING family chosen from the search', async () => {
    backend.addFamily('Familia Alvarez');
    backend.addFamily('Familia Benítez');
    await openInvitations();
    const user = userEvent.setup();
    await user.click(await screen.findByRole('button', { name: 'Nueva invitación' }));
    await user.click(screen.getByLabelText('Familia existente'));

    await user.type(screen.getByLabelText('Buscar familia por nombre'), 'Benít');
    await waitFor(() => expect(screen.queryByLabelText('Familia Alvarez')).not.toBeInTheDocument());
    await user.click(await screen.findByLabelText('Familia Benítez'));
    await user.click(screen.getByRole('button', { name: 'Crear invitación' }));

    expect(await screen.findByLabelText('Enlace de registro')).toBeInTheDocument();
    const write = backend.writes().find((r) => r.url === '/admin/invitaciones');
    expect(write?.body).toMatchObject({ familiaId: expect.stringMatching(/^fam-/) as string });
    expect(write?.body).not.toHaveProperty('nuevaFamilia');
  });

  it('requires choosing a family when inviting an existing one', async () => {
    backend.addFamily('Familia Alvarez');
    await openInvitations();
    const user = userEvent.setup();
    await user.click(await screen.findByRole('button', { name: 'Nueva invitación' }));
    await user.click(screen.getByLabelText('Familia existente'));
    await user.click(screen.getByRole('button', { name: 'Crear invitación' }));

    expect(await screen.findByText('Elegí una familia de la lista.')).toBeInTheDocument();
    expect(backend.writes()).toHaveLength(0);
  });

  it('validates the form fields before calling the backend', async () => {
    await openInvitations();
    const user = userEvent.setup();
    await user.click(await screen.findByRole('button', { name: 'Nueva invitación' }));
    await user.type(screen.getByLabelText(/^Correo sugerido/), 'no-es-un-correo');
    await user.type(screen.getByLabelText(/^Vigencia en días/), '0');
    await user.click(screen.getByRole('button', { name: 'Crear invitación' }));

    expect(await screen.findByText('Ingresá un nombre de referencia.')).toBeInTheDocument();
    expect(screen.getByText('Ingresá un correo electrónico válido.')).toBeInTheDocument();
    expect(screen.getByText('Ingresá un número de días entero y mayor a 0.')).toBeInTheDocument();
    expect(backend.writes()).toHaveLength(0);
  });

  it('shows the backend validation message (e.g. maximum validity) and keeps the form', async () => {
    await openInvitations();
    const user = userEvent.setup();
    await user.click(await screen.findByRole('button', { name: 'Nueva invitación' }));
    await user.type(screen.getByLabelText(/^Nombre de referencia de la familia/), 'Familia Z');
    await user.type(screen.getByLabelText(/^Vigencia en días/), '99');
    await user.click(screen.getByRole('button', { name: 'Crear invitación' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('supera el máximo permitido de 30 días');
    expect(screen.getByLabelText(/^Nombre de referencia de la familia/)).toHaveValue('Familia Z');
  });

  it('reports a vanished family (404) with an actionable message', async () => {
    const family = backend.addFamily('Familia Alvarez');
    await openInvitations();
    const user = userEvent.setup();
    await user.click(await screen.findByRole('button', { name: 'Nueva invitación' }));
    await user.click(screen.getByLabelText('Familia existente'));
    await user.click(await screen.findByLabelText(family.nombreReferencia));
    backend.override('post', '/admin/invitaciones', { status: 404, body: { codigo: 'FAMILIA_NO_ENCONTRADA', mensaje: 'x' } });
    await user.click(screen.getByRole('button', { name: 'Crear invitación' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('La familia elegida ya no existe o está inactiva');
  });

  it('builds the link from the current address when the backend has no FRONTEND_URL_BASE', async () => {
    backend.frontendBase = null;
    await openInvitations();
    const user = userEvent.setup();
    await user.click(await screen.findByRole('button', { name: 'Nueva invitación' }));
    await user.type(screen.getByLabelText(/^Nombre de referencia de la familia/), 'Familia Y');
    await user.click(screen.getByRole('button', { name: 'Crear invitación' }));

    const link = await screen.findByLabelText<HTMLInputElement>('Enlace de registro');
    expect(link.value).toBe(`${window.location.origin}/registro/invitacion/${link.value.split('/').pop()}`);
    expect(screen.getByText(/FRONTEND_URL_BASE/)).toBeInTheDocument();
  });

  it('shows network and server failures on creation without losing the form', async () => {
    await openInvitations();
    const user = userEvent.setup();
    await user.click(await screen.findByRole('button', { name: 'Nueva invitación' }));
    await user.type(screen.getByLabelText(/^Nombre de referencia de la familia/), 'Familia X');
    backend.override('post', '/admin/invitaciones', { status: 503, body: { codigo: 'SERVICIO_NO_DISPONIBLE', mensaje: 'x' } });
    await user.click(screen.getByRole('button', { name: 'Crear invitación' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('El servicio no está disponible');
    expect(backend.writes().filter((r) => r.url === '/admin/invitaciones')).toHaveLength(1); // never retried
  });
});

describe('revoke invitation (mock backend)', () => {
  it('revokes a pending invitation after confirmation', async () => {
    backend.seedInvitation({ status: 'PENDIENTE', family: 'Pérez' });
    await openInvitations();
    const user = userEvent.setup();
    await user.click(await screen.findByRole('button', { name: 'Revocar invitación de Pérez' }));
    expect(screen.getByRole('group', { name: 'Confirmar revocación' })).toHaveTextContent('El enlace dejará de funcionar');
    expect(backend.writes()).toHaveLength(0); // nothing happens before confirming

    await user.click(screen.getByRole('button', { name: 'Sí, revocar' }));
    const row = (await screen.findByText('Pérez')).closest('li')!;
    await waitFor(() => expect(within(row).getByText('Revocada')).toBeInTheDocument());
    expect(within(row).queryByRole('button', { name: /Revocar/ })).not.toBeInTheDocument();
    expect(backend.writes().find((r) => r.url.endsWith('/revocar'))?.csrfHeader).toBeTruthy();
  });

  it('lets the admin cancel the confirmation', async () => {
    backend.seedInvitation({ status: 'PENDIENTE', family: 'Pérez' });
    await openInvitations();
    const user = userEvent.setup();
    await user.click(await screen.findByRole('button', { name: 'Revocar invitación de Pérez' }));
    await user.click(screen.getByRole('button', { name: 'Cancelar' }));
    expect(screen.queryByRole('group', { name: 'Confirmar revocación' })).not.toBeInTheDocument();
    expect(backend.writes()).toHaveLength(0);
  });

  it('offers revoke only for pending invitations', async () => {
    for (const status of ['USADA', 'REVOCADA', 'EXPIRADA'] as const) backend.seedInvitation({ status, family: status });
    await openInvitations();
    await screen.findByText('USADA');
    expect(screen.queryByRole('button', { name: /Revocar/ })).not.toBeInTheDocument();
  });

  it('explains a 409 (already used) and refreshes the list', async () => {
    const token = backend.seedInvitation({ status: 'PENDIENTE', family: 'Pérez' });
    await openInvitations();
    const user = userEvent.setup();
    await user.click(await screen.findByRole('button', { name: 'Revocar invitación de Pérez' }));
    // The family registers between the click and the confirmation.
    backend.override('post', `/admin/invitaciones/${backend.invitationId(token)}/revocar`, {
      status: 409,
      body: { codigo: 'INVITACION_NO_REVOCABLE', mensaje: 'x' },
    });
    await user.click(screen.getByRole('button', { name: 'Sí, revocar' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('ya fue utilizada y no puede revocarse');
  });
});
