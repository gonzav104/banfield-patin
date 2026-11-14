import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { FakeAdminTutor, FakeBackend } from '@/test/fakeBackend';
import { createBackend, renderApp } from '@/test/render';

// MOCK backend (see src/test/fakeBackend.ts). Real-backend evidence lives in e2e/familias-detalle-alta.real.spec.ts.
let backend: FakeBackend;

beforeEach(() => {
  localStorage.clear();
  sessionStorage.clear();
  backend = createBackend();
});

const location = () => screen.getByTestId('location').textContent ?? '';
const detailRequests = () => backend.requests.filter((r) => r.method === 'get' && /^\/admin\/familias\/[^/?]+$/.test(r.url) && !r.url.endsWith('/listado'));
const createRequests = () => backend.writes().filter((r) => r.url === '/admin/familias');

const tutor = (over: Partial<FakeAdminTutor> = {}): FakeAdminTutor => ({
  id: `t-${Math.random().toString(16).slice(2, 8)}`,
  nombre: 'Rosa',
  apellido: 'Sintética',
  dniEnmascarado: '••••••678',
  telefono: '11 5555-0000',
  email: 'rosa@example.test',
  parentesco: 'Madre',
  activo: true,
  ...over,
});

function openDetail(id: string) {
  backend.signIn('marcos@example.com');
  renderApp(`/admin/familias/${id}`);
}

async function openCreate() {
  backend.signIn('marcos@example.com');
  renderApp('/admin/familias/nueva');
  return screen.findByRole('heading', { name: 'Nueva familia' });
}

describe('family detail (mock backend)', () => {
  it('keeps a family user out and never requests the detail', async () => {
    const family = backend.addFamily('Gómez');
    backend.signIn('lucia@example.com');
    renderApp(`/admin/familias/${family.id}`);
    expect(await screen.findByRole('heading', { name: 'No tenés permiso para ver esta sección' })).toBeInTheDocument();
    expect(detailRequests()).toHaveLength(0);
  });

  it('sends anonymous visitors to the admin login', async () => {
    const family = backend.addFamily('Gómez');
    renderApp(`/admin/familias/${family.id}`);
    expect(await screen.findByRole('heading', { name: 'Acceso administrativo' })).toBeInTheDocument();
  });

  it('shows the loading state, then the family with its tutors and the masked DNI', async () => {
    const family = backend.addFamily('Gómez', { tutores: [tutor(), tutor({ nombre: 'Luis', apellido: 'Prueba', dniEnmascarado: null, telefono: null, email: null, parentesco: null, activo: false })] });
    const held = backend.hold('get', `/admin/familias/${family.id}`);
    openDetail(family.id);
    expect(await screen.findByText('Cargando la familia…')).toBeInTheDocument();
    held.release();

    expect(await screen.findByRole('heading', { level: 1, name: 'Gómez' })).toBeInTheDocument();
    expect(detailRequests()[0]!.url).toBe(`/admin/familias/${family.id}`);
    expect(screen.getByText('Activa')).toBeInTheDocument();
    const tutors = within(screen.getByRole('list', { name: 'Tutores' })).getAllByRole('listitem');
    expect(tutors).toHaveLength(2);
    expect(tutors[0]).toHaveTextContent('Rosa Sintética');
    expect(tutors[0]).toHaveTextContent('Madre');
    expect(tutors[0]).toHaveTextContent(/DNI:\s*•{6}678/);
    expect(tutors[0]).toHaveTextContent('11 5555-0000');
    expect(tutors[0]).toHaveTextContent('rosa@example.test');
    expect(tutors[1]).toHaveTextContent('Luis Prueba');
    expect(tutors[1]).toHaveTextContent(/DNI:\s*No informado/);
    expect(tutors[1]).toHaveTextContent('Inactivo');
    expect(screen.queryByText('Cargando la familia…')).not.toBeInTheDocument();
  });

  it('marks an inactive family and offers no editing, activation or tutor management', async () => {
    const family = backend.addFamily('Pérez', { activa: false });
    openDetail(family.id);
    expect(await screen.findByRole('heading', { level: 1, name: 'Pérez' })).toBeInTheDocument();
    expect(screen.getByText('Inactiva')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /editar|activar|desactivar|agregar|eliminar/i })).not.toBeInTheDocument();
  });

  it('does not show the technical revision', async () => {
    const family = backend.addFamily('Gómez', { version: 7 });
    openDetail(family.id);
    await screen.findByRole('heading', { level: 1, name: 'Gómez' });
    expect(document.body.textContent).not.toMatch(/versi[oó]n|revisi[oó]n/i);
    expect(screen.queryByText('7')).not.toBeInTheDocument();
  });

  it('explains a family without tutors and points to invitations for access', async () => {
    const family = backend.addFamily('Gómez');
    openDetail(family.id);
    expect(await screen.findByText('Esta familia todavía no tiene tutores registrados.')).toBeInTheDocument();
    expect(screen.getByText(/El acceso de las familias se gestiona con invitaciones/)).toBeInTheDocument();
    expect(screen.queryByRole('list', { name: 'Tutores' })).not.toBeInTheDocument();
  });

  it('shows one «not found» screen for a missing family and offers the way back', async () => {
    openDetail('00000000-0000-4000-8000-000000000999');
    expect(await screen.findByRole('heading', { name: 'No encontramos esta familia' })).toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    await userEvent.setup().click(screen.getByRole('link', { name: 'Volver al listado' }));
    expect(await screen.findByRole('heading', { level: 1, name: 'Familias' })).toBeInTheDocument();
  });

  it.each(['abc/../x', 'a b', '%2e%2e', 'x'.repeat(65)])('does not even ask the backend for the malformed id «%s»', async (id) => {
    openDetail(encodeURIComponent(id));
    expect(await screen.findByRole('heading', { name: 'No encontramos esta familia' })).toBeInTheDocument();
    expect(detailRequests()).toHaveLength(0);
  });

  it('treats a 400 from the backend (an id that is not valid) as «not found»', async () => {
    backend.override('get', '/admin/familias/abc', { status: 400, body: { codigo: 'SOLICITUD_INVALIDA', mensaje: 'x' } });
    openDetail('abc');
    expect(await screen.findByRole('heading', { name: 'No encontramos esta familia' })).toBeInTheDocument();
  });

  it('shows a safe error with a working retry', async () => {
    const family = backend.addFamily('Gómez');
    backend.override('get', `/admin/familias/${family.id}`, { status: 500, body: { codigo: 'ERROR_INTERNO', mensaje: 'detalle interno' } });
    openDetail(family.id);
    expect(await screen.findByRole('alert')).toHaveTextContent('Ocurrió un problema en el servidor');
    expect(screen.getByRole('alert')).not.toHaveTextContent('detalle interno');
    await userEvent.setup().click(screen.getByRole('button', { name: 'Reintentar' }));
    expect(await screen.findByRole('heading', { level: 1, name: 'Gómez' })).toBeInTheDocument();
  });

  it('explains a network failure and a permission error', async () => {
    const family = backend.addFamily('Gómez');
    backend.override('get', `/admin/familias/${family.id}`, 'network');
    openDetail(family.id);
    expect(await screen.findByRole('alert')).toHaveTextContent('No pudimos conectar con el servidor');
  });

  it('shows the permission error when the API answers 403', async () => {
    const family = backend.addFamily('Gómez');
    backend.override('get', `/admin/familias/${family.id}`, { status: 403, body: { codigo: 'ACCESO_DENEGADO', mensaje: 'x' } });
    openDetail(family.id);
    expect(await screen.findByRole('alert')).toHaveTextContent('No tenés permiso para ver esta familia.');
  });

  it('is reached from a row of the list, and from the detail you can go back', async () => {
    const family = backend.addFamily('Gómez');
    backend.signIn('marcos@example.com');
    renderApp('/admin/familias');
    const user = userEvent.setup();
    await user.click(await screen.findByRole('link', { name: 'Gómez' }));
    expect(await screen.findByRole('heading', { level: 1, name: 'Gómez' })).toBeInTheDocument();
    expect(location()).toBe(`/admin/familias/${family.id}`);
    await user.click(screen.getByRole('link', { name: 'Volver al listado' }));
    expect(await screen.findByRole('heading', { level: 1, name: 'Familias' })).toBeInTheDocument();
  });

  it('keeps the family name and the tutors’ data out of the address and storage', async () => {
    const family = backend.addFamily('Gómez', { tutores: [tutor()] });
    openDetail(family.id);
    await screen.findByRole('heading', { level: 1, name: 'Gómez' });
    expect(location()).not.toMatch(/G[óo]mez|rosa|678/i);
    const stored = JSON.stringify({ ...localStorage, ...sessionStorage });
    expect(stored).not.toMatch(/G[óo]mez|rosa|678/i);
  });
});

describe('create family (mock backend)', () => {
  it('is reachable from the list and shows the form with the explanation about accounts', async () => {
    backend.signIn('marcos@example.com');
    renderApp('/admin/familias');
    await userEvent.setup().click(await screen.findByRole('link', { name: 'Nueva familia' }));
    expect(await screen.findByRole('heading', { name: 'Nueva familia' })).toBeInTheDocument();
    expect(location()).toBe('/admin/familias/nueva');
    expect(screen.getByLabelText(/Nombre de referencia/)).toHaveAttribute('maxlength', '150');
    expect(screen.getByText(/Crear una familia no genera una cuenta de usuario/)).toBeInTheDocument();
    expect(screen.getByText(/enviá una invitación/)).toBeInTheDocument();
    expect(createRequests()).toHaveLength(0);
  });

  it('keeps a family user out', async () => {
    backend.signIn('lucia@example.com');
    renderApp('/admin/familias/nueva');
    expect(await screen.findByRole('heading', { name: 'No tenés permiso para ver esta sección' })).toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: 'Nueva familia' })).not.toBeInTheDocument();
  });

  it.each(['', '   '])('does not send an empty name («%s») and asks for one', async (value) => {
    await openCreate();
    const user = userEvent.setup();
    if (value) await user.type(screen.getByLabelText(/Nombre de referencia/), value);
    await user.click(screen.getByRole('button', { name: 'Crear familia' }));
    expect(await screen.findByText('Ingresá un nombre de referencia.')).toBeInTheDocument();
    expect(screen.getByLabelText(/Nombre de referencia/)).toHaveAttribute('aria-invalid', 'true');
    expect(createRequests()).toHaveLength(0);
  });

  it('rejects a name longer than 150 characters without sending it', async () => {
    await openCreate();
    const user = userEvent.setup();
    const box = screen.getByLabelText(/Nombre de referencia/);
    box.removeAttribute('maxlength'); // a pasted value can exceed the attribute in some browsers: the schema must hold too
    await user.click(box);
    await user.paste('x'.repeat(151));
    await user.click(screen.getByRole('button', { name: 'Crear familia' }));
    expect(await screen.findByText('Máximo 150 caracteres.')).toBeInTheDocument();
    expect(createRequests()).toHaveLength(0);
  });

  it('creates the family with the trimmed name, a CSRF header and goes to its detail with a confirmation', async () => {
    await openCreate();
    const user = userEvent.setup();
    await user.type(screen.getByLabelText(/Nombre de referencia/), '  Familia Nueva  ');
    await user.click(screen.getByRole('button', { name: 'Crear familia' }));

    expect(await screen.findByRole('heading', { level: 1, name: 'Familia Nueva' })).toBeInTheDocument();
    expect(createRequests()).toHaveLength(1);
    expect(createRequests()[0]!.body).toEqual({ nombreReferencia: 'Familia Nueva' });
    expect(createRequests()[0]!.csrfHeader).toBeTruthy();
    expect(location()).toMatch(/^\/admin\/familias\/[^/]+$/);
    expect(location()).not.toMatch(/Nueva/);
    expect(screen.getByText(/La familia se creó correctamente/)).toBeInTheDocument();
    expect(screen.getByText('Esta familia todavía no tiene tutores registrados.')).toBeInTheDocument();
  });

  it('shows the new family in the list afterwards', async () => {
    await openCreate();
    const user = userEvent.setup();
    await user.type(screen.getByLabelText(/Nombre de referencia/), 'Familia Nueva');
    await user.click(screen.getByRole('button', { name: 'Crear familia' }));
    await screen.findByRole('heading', { level: 1, name: 'Familia Nueva' });
    await user.click(screen.getByRole('link', { name: 'Volver al listado' }));
    expect(await screen.findByRole('link', { name: 'Familia Nueva' })).toBeInTheDocument();
  });

  it('disables the button while the request is in flight so a double click cannot create two', async () => {
    await openCreate();
    const user = userEvent.setup();
    await user.type(screen.getByLabelText(/Nombre de referencia/), 'Una sola');
    const held = backend.hold('post', '/admin/familias');
    const button = screen.getByRole('button', { name: 'Crear familia' });
    await user.click(button);
    expect(await screen.findByRole('button', { name: 'Creando…' })).toBeDisabled();
    await user.click(screen.getByRole('button', { name: 'Creando…' }));
    await user.keyboard('{Enter}');
    held.release();
    expect(await screen.findByRole('heading', { level: 1, name: 'Una sola' })).toBeInTheDocument();
    expect(createRequests()).toHaveLength(1);
  });

  it('shows a fixed message for a validation error from the backend, without echoing its text', async () => {
    await openCreate();
    backend.override('post', '/admin/familias', { status: 400, body: { codigo: 'VALIDACION', mensaje: 'texto del servidor', detalles: [{ campo: 'nombreReferencia', mensaje: 'detalle del servidor' }] } });
    const user = userEvent.setup();
    await user.type(screen.getByLabelText(/Nombre de referencia/), 'Algo');
    await user.click(screen.getByRole('button', { name: 'Crear familia' }));
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Revisá el nombre de referencia');
    expect(alert).not.toHaveTextContent('servidor');
    expect(screen.getByLabelText(/Nombre de referencia/)).toHaveValue('Algo');
  });

  it('explains a permission error and a conflict', async () => {
    await openCreate();
    const user = userEvent.setup();
    await user.type(screen.getByLabelText(/Nombre de referencia/), 'Algo');
    backend.override('post', '/admin/familias', { status: 403, body: { codigo: 'ACCESO_DENEGADO', mensaje: 'x' } });
    await user.click(screen.getByRole('button', { name: 'Crear familia' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('No tenés permiso para crear familias.');

    backend.override('post', '/admin/familias', { status: 409, body: { codigo: 'CONFLICTO_CONCURRENCIA', mensaje: 'x' } });
    await user.click(screen.getByRole('button', { name: 'Crear familia' }));
    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('conflicto'));
  });

  it.each([
    ['a network failure or timeout', 'network' as const],
    ['a 500', { status: 500, body: { codigo: 'ERROR_INTERNO', mensaje: 'x' } }],
    ['a 503', { status: 503, body: { codigo: 'NO_DISPONIBLE', mensaje: 'x' } }],
  ])('does not assume the family was not created after %s, and never retries by itself', async (_label, failure) => {
    await openCreate();
    backend.override('post', '/admin/familias', failure);
    const user = userEvent.setup();
    await user.type(screen.getByLabelText(/Nombre de referencia/), 'Incierta');
    await user.click(screen.getByRole('button', { name: 'Crear familia' }));

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('No pudimos confirmar si la familia se creó');
    expect(alert).toHaveTextContent('buscala en el listado');
    expect(within(alert).getByRole('link', { name: 'Revisar el listado' })).toHaveAttribute('href', '/admin/familias');
    await new Promise((resolve) => setTimeout(resolve, 400));
    expect(createRequests()).toHaveLength(1); // no automatic retry
    expect(screen.getByLabelText(/Nombre de referencia/)).toHaveValue('Incierta'); // the data is kept
    expect(screen.getByRole('button', { name: 'Crear familia' })).toBeEnabled(); // a manual retry stays possible
  });

  it('keeps the name out of the address, storage and console', async () => {
    const logged: unknown[][] = [];
    const spies = (['log', 'info', 'warn', 'error', 'debug'] as const).map((level) => vi.spyOn(console, level).mockImplementation(() => undefined));
    try {
      await openCreate();
      const user = userEvent.setup();
      await user.type(screen.getByLabelText(/Nombre de referencia/), 'Reservada');
      await user.click(screen.getByRole('button', { name: 'Crear familia' }));
      await screen.findByRole('heading', { level: 1, name: 'Reservada' });
      expect(location()).not.toContain('Reservada');
      expect(JSON.stringify({ ...localStorage, ...sessionStorage })).not.toContain('Reservada');
    } finally {
      logged.push(...spies.flatMap((spy) => spy.mock.calls));
      spies.forEach((spy) => spy.mockRestore());
    }
    expect(logged.flat().join(' ')).not.toContain('Reservada');
  });

  it('cancels back to the list without sending anything', async () => {
    await openCreate();
    await userEvent.setup().click(screen.getByRole('link', { name: 'Cancelar' }));
    expect(await screen.findByRole('heading', { level: 1, name: 'Familias' })).toBeInTheDocument();
    expect(createRequests()).toHaveLength(0);
  });
});
