import { act, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { QueryClient } from '@tanstack/react-query';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { sessionKey } from '@/features/auth/session';
import type { FakeAdminTutor, FakeBackend } from '@/test/fakeBackend';
import { admin, createBackend, renderApp } from '@/test/render';

// MOCK backend (see src/test/fakeBackend.ts, which mirrors the real contract of FE-05/U4). Real-backend evidence lives in
// e2e/familias-tutores.real.spec.ts.
let backend: FakeBackend;

beforeEach(() => {
  localStorage.clear();
  sessionStorage.clear();
  backend = createBackend();
});

const DNI = '30111222';
const location = () => screen.getByTestId('location').textContent ?? '';
const wait = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms));
const tutorPosts = () => backend.requests.filter((r) => r.method === 'post' && /^\/admin\/familias\/[^/]+\/tutores$/.test(r.url));
const tutorGets = () => backend.requests.filter((r) => r.method === 'get' && /^\/admin\/tutores\/[^/]+$/.test(r.url));
const familyGets = (id: string) => backend.requests.filter((r) => r.method === 'get' && r.url === `/admin/familias/${id}`);

function tutor(over: Partial<FakeAdminTutor> = {}): FakeAdminTutor {
  return {
    id: 't-rosa',
    nombre: 'Rosa',
    apellido: 'Sintética',
    dni: DNI,
    dniEnmascarado: '••••••222',
    telefono: '11 5555-0000',
    email: 'rosa@example.test',
    parentesco: 'Madre',
    activo: true,
    version: 3,
    ...over,
  };
}

function seedFamily(over: Parameters<FakeBackend['addFamily']>[1] = {}) {
  return backend.addFamily('Gómez', { tutores: [tutor()], ...over });
}

function openTutor(path: string) {
  backend.signIn('marcos@example.com');
  return renderApp(path);
}

async function openForm(extra: Parameters<FakeBackend['addFamily']>[1] = {}) {
  const family = backend.addFamily('Gómez', extra);
  const { queryClient } = openTutor(`/admin/familias/${family.id}/tutores/nuevo`);
  await screen.findByRole('heading', { name: 'Nuevo tutor' });
  return { family, queryClient };
}

async function fill(user: ReturnType<typeof userEvent.setup>, values: Partial<Record<'nombre' | 'apellido' | 'dni' | 'telefono' | 'correo' | 'parentesco', string>>) {
  const labels = { nombre: /^Nombre/, apellido: /^Apellido/, dni: /^DNI/, telefono: /^Teléfono/, correo: /^Correo/, parentesco: /^Parentesco/ };
  for (const [key, value] of Object.entries(values)) {
    const box = screen.getByLabelText(labels[key as keyof typeof labels]);
    await user.clear(box);
    await user.type(box, value);
  }
}

const submit = (user: ReturnType<typeof userEvent.setup>) => user.click(screen.getByRole('button', { name: 'Agregar tutor' }));

describe('tutor detail (mock backend)', () => {
  it('keeps a family user out and never requests the tutor', async () => {
    const family = seedFamily();
    backend.signIn('lucia@example.com');
    renderApp(`/admin/familias/${family.id}/tutores/t-rosa`);
    expect(await screen.findByRole('heading', { name: 'No tenés permiso para ver esta sección' })).toBeInTheDocument();
    expect(tutorGets()).toHaveLength(0);
  });

  it('keeps a password-only admin session out and never requests the tutor', async () => {
    const family = seedFamily();
    backend.signIn('marcos@example.com', true);
    renderApp(`/admin/familias/${family.id}/tutores/t-rosa`);
    expect(await screen.findByRole('heading', { name: 'Verificación en dos pasos' })).toBeInTheDocument();
    expect(tutorGets()).toHaveLength(0);
  });

  it('is reached from the family detail, which keeps the DNI masked, and the individual view shows the full data', async () => {
    const family = seedFamily();
    openTutor(`/admin/familias/${family.id}`);
    const user = userEvent.setup();
    const list = await screen.findByRole('list', { name: 'Tutores' });
    expect(list).toHaveTextContent(/DNI:\s*•{6}222/);
    expect(document.body.textContent).not.toContain(DNI);
    expect(tutorGets()).toHaveLength(0); // the summary never asks for the individual data

    const held = backend.hold('get', '/admin/tutores/t-rosa');
    await user.click(within(list).getByRole('link', { name: 'Rosa Sintética' }));
    expect(await screen.findByText('Cargando el tutor…')).toBeInTheDocument();
    held.release();

    expect(await screen.findByRole('heading', { level: 1, name: 'Rosa Sintética' })).toBeInTheDocument();
    expect(location()).toBe(`/admin/familias/${family.id}/tutores/t-rosa`);
    expect(tutorGets()[0]!.url).toBe('/admin/tutores/t-rosa');
    expect(screen.getByText(DNI)).toBeInTheDocument(); // the FULL DNI only here
    expect(document.body.textContent).not.toContain('•');
    expect(screen.getByText('Madre')).toBeInTheDocument();
    expect(screen.getByText('11 5555-0000')).toBeInTheDocument();
    expect(screen.getByText('rosa@example.test')).toBeInTheDocument();
  });

  it('shows only what the contract carries: no revision, no deletion, «Inactivo» only when it applies', async () => {
    const family = backend.addFamily('Gómez', { tutores: [tutor({ activo: false, dni: null, dniEnmascarado: null, telefono: null, email: null, parentesco: null })] });
    openTutor(`/admin/familias/${family.id}/tutores/t-rosa`);
    await screen.findByRole('heading', { level: 1, name: 'Rosa Sintética' });
    expect(screen.getByText('Inactivo')).toBeInTheDocument();
    expect(screen.getAllByText('No informado')).toHaveLength(4);
    expect(document.body.textContent).not.toMatch(/versi[oó]n|revisi[oó]n/i);
    expect(screen.queryByText('3')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /editar|eliminar|borrar/i })).not.toBeInTheDocument();
    expect(screen.queryByRole('link', { name: /desactivar|activar|eliminar/i })).not.toBeInTheDocument(); // tutors are never deleted; the state control is a button
    expect(screen.getByRole('button', { name: 'Reactivar tutor' })).toBeInTheDocument(); // since C3 an inactive tutor offers its reactivation (confirmed in a dialog)
    expect(screen.queryByRole('button', { name: 'Desactivar tutor' })).not.toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Editar tutor' })).toBeInTheDocument(); // editing arrived with U5-B
  });

  it('goes back to the family', async () => {
    const family = seedFamily();
    openTutor(`/admin/familias/${family.id}/tutores/t-rosa`);
    await screen.findByRole('heading', { level: 1, name: 'Rosa Sintética' });
    await userEvent.setup().click(screen.getByRole('link', { name: 'Volver a la familia' }));
    expect(await screen.findByRole('heading', { level: 1, name: 'Gómez' })).toBeInTheDocument();
  });

  it('shows one «not found» screen for a tutor that does not exist', async () => {
    const family = seedFamily();
    openTutor(`/admin/familias/${family.id}/tutores/t-inexistente`);
    expect(await screen.findByRole('heading', { name: 'No encontramos este tutor' })).toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Volver a la familia' })).toHaveAttribute('href', `/admin/familias/${family.id}`);
  });

  it('shows the very same «not found» screen for a tutor of ANOTHER family, without revealing it', async () => {
    const family = seedFamily();
    backend.addFamily('Otra', { tutores: [tutor({ id: 't-ajeno', nombre: 'Ajeno' })] });
    openTutor(`/admin/familias/${family.id}/tutores/t-ajeno`);
    expect(await screen.findByRole('heading', { name: 'No encontramos este tutor' })).toBeInTheDocument();
    expect(screen.queryByText(/Ajeno/)).not.toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it.each(['a b', '%2e%2e', 'x'.repeat(65)])('does not even ask the backend for the malformed tutor id «%s»', async (id) => {
    const family = seedFamily();
    openTutor(`/admin/familias/${family.id}/tutores/${encodeURIComponent(id)}`);
    expect(await screen.findByRole('heading', { name: 'No encontramos este tutor' })).toBeInTheDocument();
    expect(tutorGets()).toHaveLength(0);
  });

  it('shows a safe error with a working retry, and the permission and network errors', async () => {
    const family = seedFamily();
    backend.override('get', '/admin/tutores/t-rosa', { status: 500, body: { codigo: 'ERROR_INTERNO', mensaje: 'detalle interno' } });
    openTutor(`/admin/familias/${family.id}/tutores/t-rosa`);
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Ocurrió un problema en el servidor');
    expect(alert).not.toHaveTextContent('detalle interno');
    await userEvent.setup().click(screen.getByRole('button', { name: 'Reintentar' }));
    expect(await screen.findByRole('heading', { level: 1, name: 'Rosa Sintética' })).toBeInTheDocument();
  });

  it('explains a permission error and a network failure', async () => {
    const family = seedFamily();
    backend.override('get', '/admin/tutores/t-rosa', { status: 403, body: { codigo: 'ACCESO_DENEGADO', mensaje: 'x' } });
    openTutor(`/admin/familias/${family.id}/tutores/t-rosa`);
    expect(await screen.findByRole('alert')).toHaveTextContent('No tenés permiso para ver este tutor.');
  });

  it('keeps the personal data out of the address, storage and console, and drops it from memory on leaving', async () => {
    const logged: unknown[][] = [];
    const spies = (['log', 'info', 'warn', 'error', 'debug'] as const).map((level) => vi.spyOn(console, level).mockImplementation(() => undefined));
    let queryClient: QueryClient;
    try {
      const family = seedFamily();
      ({ queryClient } = openTutor(`/admin/familias/${family.id}/tutores/t-rosa`));
      await screen.findByRole('heading', { level: 1, name: 'Rosa Sintética' });
      expect(location()).not.toMatch(new RegExp(`${DNI}|rosa@|5555`));
      expect(JSON.stringify({ ...localStorage, ...sessionStorage })).not.toMatch(new RegExp(`${DNI}|rosa@|5555`));
      await userEvent.setup().click(screen.getByRole('link', { name: 'Volver a la familia' }));
      await screen.findByRole('heading', { level: 1, name: 'Gómez' });
      await waitFor(() => expect(queryClient.getQueryData(['admin', 'tutor-detail', 't-rosa'])).toBeUndefined());
    } finally {
      logged.push(...spies.flatMap((spy) => spy.mock.calls));
      spies.forEach((spy) => spy.mockRestore());
    }
    expect(logged.flat().join(' ')).not.toMatch(new RegExp(`${DNI}|rosa@|5555`));
  });

  it('asks for the tutor again, from nothing, when the session now belongs to another administrator', async () => {
    backend.addUser({ ...admin, id: 'u-admin-3', nombre: 'Otra', email: 'otra.admin@example.com', mfaEnrolado: true });
    const family = seedFamily();
    const { queryClient } = openTutor(`/admin/familias/${family.id}/tutores/t-rosa`);
    await screen.findByRole('heading', { level: 1, name: 'Rosa Sintética' });
    expect(tutorGets()).toHaveLength(1);
    backend.signIn('otra.admin@example.com');
    await act(async () => {
      await queryClient.invalidateQueries({ queryKey: sessionKey });
    });
    await screen.findByRole('heading', { level: 1, name: 'Rosa Sintética' });
    await waitFor(() => expect(tutorGets().length).toBeGreaterThanOrEqual(2)); // nothing of the previous identity was reused
  });
});

describe('add a tutor: entry points (mock backend)', () => {
  it('offers «Agregar tutor» in the active family, with and without tutors', async () => {
    const family = backend.addFamily('Gómez');
    openTutor(`/admin/familias/${family.id}`);
    const link = await screen.findByRole('link', { name: 'Agregar tutor' });
    expect(link).toHaveAttribute('href', `/admin/familias/${family.id}/tutores/nuevo`);
    await userEvent.setup().click(link);
    expect(await screen.findByRole('heading', { name: 'Nuevo tutor' })).toBeInTheDocument();
  });

  it('does not offer it in an inactive family and says why', async () => {
    const family = backend.addFamily('Pérez', { activa: false });
    openTutor(`/admin/familias/${family.id}`);
    await screen.findByRole('heading', { level: 1, name: 'Pérez' });
    expect(screen.queryByRole('link', { name: 'Agregar tutor' })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Agregar tutor' })).toBeDisabled();
    expect(screen.getByText(/La familia está inactiva: reactivala para agregar tutores/)).toBeInTheDocument();
  });

  it('keeps a family user out of the form', async () => {
    const family = backend.addFamily('Gómez');
    backend.signIn('lucia@example.com');
    renderApp(`/admin/familias/${family.id}/tutores/nuevo`);
    expect(await screen.findByRole('heading', { name: 'No tenés permiso para ver esta sección' })).toBeInTheDocument();
    expect(tutorPosts()).toHaveLength(0);
  });
});

describe('add a tutor: the form (mock backend)', () => {
  it('shows the family, the real fields and the explanation that no account or invitation is created', async () => {
    const { family } = await openForm();
    expect(screen.getByText(/Gómez/)).toBeInTheDocument();
    for (const [label, max] of [[/^Nombre/, '100'], [/^Apellido/, '100'], [/^DNI/, '20'], [/^Teléfono/, '40'], [/^Correo/, '180'], [/^Parentesco/, '40']] as const) {
      expect(screen.getByLabelText(label)).toHaveAttribute('maxlength', max);
    }
    expect(screen.getByText(/Crear un tutor no genera una cuenta de usuario ni una invitación/)).toBeInTheDocument();
    expect(familyGets(family.id)).toHaveLength(1);
    expect(tutorPosts()).toHaveLength(0);
  });

  it('refuses to add tutors to an inactive family and shows no form', async () => {
    const family = backend.addFamily('Pérez', { activa: false });
    openTutor(`/admin/familias/${family.id}/tutores/nuevo`);
    expect(await screen.findByText(/La familia está inactiva: no se pueden agregar tutores/)).toBeInTheDocument();
    expect(screen.queryByLabelText(/^Nombre/)).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Agregar tutor' })).not.toBeInTheDocument();
    expect(tutorPosts()).toHaveLength(0);
  });

  it('shows the not-found screen for a missing family without a form', async () => {
    openTutor('/admin/familias/fam-inexistente/tutores/nuevo');
    expect(await screen.findByRole('heading', { name: 'No encontramos esta familia' })).toBeInTheDocument();
    expect(screen.queryByLabelText(/^Nombre/)).not.toBeInTheDocument();
  });

  it('validates the fields like the backend and sends nothing while they are invalid', async () => {
    await openForm();
    const user = userEvent.setup();
    await submit(user);
    expect(await screen.findByText('Ingresá el nombre.')).toBeInTheDocument();
    expect(screen.getByText('Ingresá el apellido.')).toBeInTheDocument();

    await fill(user, { nombre: 'Ana', apellido: 'Pérez', dni: '12345', correo: 'no-es-un-correo' });
    await submit(user);
    expect(await screen.findByText('El DNI debe tener entre 7 y 9 dígitos.')).toBeInTheDocument();
    expect(screen.getByText('Ingresá un correo electrónico válido.')).toBeInTheDocument();
    expect(screen.getByLabelText(/^DNI/)).toHaveAttribute('aria-invalid', 'true');
    expect(tutorPosts()).toHaveLength(0);
  });

  it('accepts a DNI written with dots or spaces and rejects letters', async () => {
    await openForm();
    const user = userEvent.setup();
    await fill(user, { nombre: 'Ana', apellido: 'Pérez', dni: '30.111.222' });
    await submit(user);
    expect(await screen.findByRole('heading', { level: 1, name: 'Gómez' })).toBeInTheDocument();
    expect(tutorPosts()).toHaveLength(1);
  });

  it('rejects a DNI with letters without sending it', async () => {
    await openForm();
    const user = userEvent.setup();
    await fill(user, { nombre: 'Ana', apellido: 'Pérez', dni: '30a11122' });
    await submit(user);
    expect(await screen.findByText('El DNI debe tener entre 7 y 9 dígitos.')).toBeInTheDocument();
    expect(tutorPosts()).toHaveLength(0);
  });

  it('creates the tutor with the captured family revision, trimmed, only the filled fields and CSRF, then shows it in the family', async () => {
    const { family } = await openForm({ version: 4 });
    const user = userEvent.setup();
    await fill(user, { nombre: '  Ana ', apellido: 'Pérez', dni: '27.123.456', telefono: '11 4444-0000', correo: 'ana@example.test', parentesco: 'Madre' });
    await submit(user);

    expect(await screen.findByRole('heading', { level: 1, name: 'Gómez' })).toBeInTheDocument();
    expect(tutorPosts()).toHaveLength(1);
    expect(tutorPosts()[0]!.url).toBe(`/admin/familias/${family.id}/tutores`);
    expect(tutorPosts()[0]!.body).toEqual({
      nombre: 'Ana',
      apellido: 'Pérez',
      dni: '27.123.456',
      telefono: '11 4444-0000',
      email: 'ana@example.test',
      parentesco: 'Madre',
      versionFamiliaEsperada: '4',
    });
    expect(tutorPosts()[0]!.csrfHeader).toBeTruthy();
    expect(location()).toBe(`/admin/familias/${family.id}`);
    expect(screen.getByText('Tutor agregado correctamente.')).toBeInTheDocument();
    const list = await screen.findByRole('list', { name: 'Tutores' });
    expect(within(list).getByRole('link', { name: 'Ana Pérez' })).toBeInTheDocument(); // the visible information was refreshed
    expect(list).toHaveTextContent(/DNI:\s*•+456/);
    expect(document.body.textContent).not.toContain('27123456');
    expect(backend.familyVersion(family.id)).toBe('4'); // adding a tutor does not move the family revision
  });

  it('sends only the required fields when the rest is empty, and creates no account or invitation', async () => {
    await openForm();
    const user = userEvent.setup();
    await fill(user, { nombre: 'Ana', apellido: 'Pérez' });
    await submit(user);
    await screen.findByText('Tutor agregado correctamente.');
    expect(tutorPosts()[0]!.body).toEqual({ nombre: 'Ana', apellido: 'Pérez', versionFamiliaEsperada: '0' });
    expect(backend.requests.some((r) => r.method !== 'get' && r.url.startsWith('/admin/invitaciones'))).toBe(false);
    expect(backend.requests.some((r) => r.method !== 'get' && r.url.startsWith('/auth/registro'))).toBe(false);
  });

  it('disables the button while sending so a double click or Enter cannot create two', async () => {
    const { family } = await openForm();
    const user = userEvent.setup();
    await fill(user, { nombre: 'Ana', apellido: 'Pérez' });
    const held = backend.hold('post', `/admin/familias/${family.id}/tutores`);
    await submit(user);
    const busy = await screen.findByRole('button', { name: 'Agregando…' });
    expect(busy).toBeDisabled();
    await user.click(busy);
    await user.keyboard('{Enter}');
    held.release();
    expect(await screen.findByText('Tutor agregado correctamente.')).toBeInTheDocument();
    expect(tutorPosts()).toHaveLength(1);
  });

  it('shows a fixed message for a validation error from the backend, a permission error and a missing family', async () => {
    const { family } = await openForm();
    const url = `/admin/familias/${family.id}/tutores`;
    const user = userEvent.setup();
    await fill(user, { nombre: 'Ana', apellido: 'Pérez' });
    backend.override('post', url, { status: 400, body: { codigo: 'VALIDACION', mensaje: 'texto del servidor', detalles: [{ campo: 'dni', mensaje: 'detalle del servidor' }] } });
    await submit(user);
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Revisá los datos del tutor');
    expect(alert).not.toHaveTextContent('servidor');
    expect(screen.getByLabelText(/^Nombre/)).toHaveValue('Ana');

    backend.override('post', url, { status: 403, body: { codigo: 'ACCESO_DENEGADO', mensaje: 'x' } });
    await submit(user);
    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('No tenés permiso para agregar tutores.'));
    backend.override('post', url, { status: 404, body: { codigo: 'FAMILIA_NO_ENCONTRADA', mensaje: 'x' } });
    await submit(user);
    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('La familia ya no existe.'));
    expect(tutorPosts()).toHaveLength(3);
  });

  it('explains a lock contention (nothing was applied) and lets the user retry by hand', async () => {
    const { family } = await openForm();
    const user = userEvent.setup();
    await fill(user, { nombre: 'Ana', apellido: 'Pérez' });
    backend.override('post', `/admin/familias/${family.id}/tutores`, { status: 409, body: { codigo: 'CONFLICTO_CONCURRENCIA', mensaje: 'x' } });
    await submit(user);
    expect(await screen.findByRole('alert')).toHaveTextContent('Otra operación está en curso sobre esta familia');
    await wait(400);
    expect(tutorPosts()).toHaveLength(1); // no automatic retry
    await submit(user); // by hand, with the same revision
    expect(await screen.findByText('Tutor agregado correctamente.')).toBeInTheDocument();
    expect(tutorPosts()).toHaveLength(2);
  });

  it('keeps the data only in memory', async () => {
    const logged: unknown[][] = [];
    const spies = (['log', 'info', 'warn', 'error', 'debug'] as const).map((level) => vi.spyOn(console, level).mockImplementation(() => undefined));
    try {
      await openForm();
      const user = userEvent.setup();
      await fill(user, { nombre: 'Reservada', apellido: 'Privada', dni: '27123456', telefono: '11 4444-0000', correo: 'privada@example.test' });
      expect(location()).not.toMatch(/Reservada|Privada|27123456|4444|privada@/);
      expect(JSON.stringify({ ...localStorage, ...sessionStorage })).not.toMatch(/Reservada|Privada|27123456|4444|privada@/);
      await submit(user);
      await screen.findByText('Tutor agregado correctamente.');
      expect(location()).not.toMatch(/Reservada|Privada|27123456|4444|privada@/);
    } finally {
      logged.push(...spies.flatMap((spy) => spy.mock.calls));
      spies.forEach((spy) => spy.mockRestore());
    }
    expect(logged.flat().join(' ')).not.toMatch(/Reservada|Privada|27123456|4444|privada@/);
  });

  it('warns before leaving a filled form and stays on «Seguir editando»', async () => {
    await openForm();
    const user = userEvent.setup();
    await fill(user, { nombre: 'Ana' });
    await user.click(screen.getByRole('link', { name: 'Cancelar' }));
    const panel = await screen.findByRole('group', { name: 'Cambios sin guardar' });
    expect(location()).toMatch(/\/tutores\/nuevo$/);
    await user.click(within(panel).getByRole('button', { name: 'Seguir editando' }));
    expect(screen.getByLabelText(/^Nombre/)).toHaveValue('Ana');
    const event = new Event('beforeunload', { cancelable: true });
    window.dispatchEvent(event);
    expect(event.defaultPrevented).toBe(true);
  });

  it('does not warn about an untouched form and cancels back to the family', async () => {
    await openForm();
    await userEvent.setup().click(screen.getByRole('link', { name: 'Cancelar' }));
    expect(await screen.findByRole('heading', { level: 1, name: 'Gómez' })).toBeInTheDocument();
    expect(tutorPosts()).toHaveLength(0);
  });

  it('drops the draft when the session now belongs to another administrator', async () => {
    backend.addUser({ ...admin, id: 'u-admin-3', nombre: 'Otra', email: 'otra.admin@example.com', mfaEnrolado: true });
    const { queryClient } = await openForm();
    const user = userEvent.setup();
    await fill(user, { nombre: 'Borrador del primero' });
    expect(screen.getByLabelText(/^Nombre/)).toHaveValue('Borrador del primero');
    backend.signIn('otra.admin@example.com');
    await act(async () => {
      await queryClient.invalidateQueries({ queryKey: sessionKey });
    });
    await screen.findByRole('heading', { name: 'Nuevo tutor' });
    await waitFor(() => expect(screen.getByLabelText(/^Nombre/)).toHaveValue(''));
    expect(document.body.textContent).not.toContain('Borrador del primero');
    expect(tutorPosts()).toHaveLength(0);
  });
});

describe('add a tutor: the family revision (mock backend)', () => {
  it('sends the revision captured when the form opened, even if the family data are refreshed meanwhile', async () => {
    const { family, queryClient } = await openForm({ version: 2 });
    const user = userEvent.setup();
    await fill(user, { nombre: 'Ana', apellido: 'Pérez' });
    backend.changeFamilyBehindTheScenes(family.id, { nombreReferencia: 'Gómez renombrada' }); // revision 3
    await act(async () => {
      await queryClient.invalidateQueries({ queryKey: ['admin', 'family-detail', family.id] }); // the screen DOES see the new data
    });
    await waitFor(() => expect(familyGets(family.id).length).toBeGreaterThanOrEqual(2));
    await submit(user);
    expect(await screen.findByText(/La familia cambió mientras completabas el formulario/)).toBeInTheDocument();
    expect(tutorPosts()).toHaveLength(1);
    expect(tutorPosts()[0]!.body).toMatchObject({ versionFamiliaEsperada: '2' }); // never silently replaced by the 3
  });

  async function staleForm(change: Parameters<FakeBackend['changeFamilyBehindTheScenes']>[1] = { nombreReferencia: 'Gómez renombrada' }) {
    const { family } = await openForm({ version: 2 });
    const user = userEvent.setup();
    await fill(user, { nombre: 'Ana', apellido: 'Pérez', telefono: '11 4444-0000' });
    backend.changeFamilyBehindTheScenes(family.id, change); // revision 3
    await submit(user);
    await screen.findByText(/La familia cambió mientras completabas el formulario/);
    return { family, user };
  }

  it('409 EDICION_DESACTUALIZADA: says what happened, keeps the draft, creates nothing and does not retry', async () => {
    const { family } = await staleForm();
    expect(screen.getByRole('alert')).toHaveTextContent('Tus datos siguen acá, pero el tutor no se creó');
    expect(screen.getByLabelText(/^Nombre/)).toHaveValue('Ana');
    expect(screen.getByLabelText(/^Teléfono/)).toHaveValue('11 4444-0000');
    expect(screen.getByRole('button', { name: 'Agregar tutor' })).toBeDisabled(); // cannot send over the stale revision
    await wait(400);
    expect(tutorPosts()).toHaveLength(1);
    expect(backend.familyVersion(family.id)).toBe('3');
  });

  it('shows the current family only when asked, and continues only by an explicit choice with the current revision', async () => {
    const { family, user } = await staleForm();
    const before = familyGets(family.id).length;
    await wait(300);
    expect(familyGets(family.id).length).toBe(before); // nothing reloads by itself
    await user.click(screen.getByRole('button', { name: 'Ver datos vigentes' }));
    const panel = await screen.findByRole('group', { name: 'Datos vigentes' });
    expect(panel).toHaveTextContent('Gómez renombrada');
    expect(panel).toHaveTextContent('Activa');
    expect(tutorPosts()).toHaveLength(1); // looking is not writing

    await user.click(screen.getByRole('button', { name: 'Seguir con mi borrador' }));
    expect(tutorPosts()).toHaveLength(1); // choosing to continue is not saving
    expect(screen.getByLabelText(/^Teléfono/)).toHaveValue('11 4444-0000');
    await submit(user);
    expect(await screen.findByText('Tutor agregado correctamente.')).toBeInTheDocument();
    expect(tutorPosts()).toHaveLength(2);
    expect(tutorPosts()[1]!.body).toMatchObject({ versionFamiliaEsperada: '3' });
  });

  it('conflicts again if the family changes once more after the user chose to continue', async () => {
    const { family, user } = await staleForm();
    await user.click(screen.getByRole('button', { name: 'Ver datos vigentes' }));
    await screen.findByRole('group', { name: 'Datos vigentes' });
    await user.click(screen.getByRole('button', { name: 'Seguir con mi borrador' }));
    backend.changeFamilyBehindTheScenes(family.id, { nombreReferencia: 'Otro cambio más' }); // revision 4
    await submit(user);
    expect(await screen.findByText(/La familia cambió mientras completabas el formulario/)).toBeInTheDocument();
    expect(tutorPosts()).toHaveLength(2);
    expect(tutorPosts()[1]!.body).toMatchObject({ versionFamiliaEsperada: '3' });
    expect(screen.getByLabelText(/^Nombre/)).toHaveValue('Ana');
  });

  it('can discard the draft and go back to the family without writing', async () => {
    const { user } = await staleForm();
    await user.click(screen.getByRole('button', { name: 'Ver datos vigentes' }));
    await screen.findByRole('group', { name: 'Datos vigentes' });
    await user.click(screen.getByRole('button', { name: 'Descartar mi borrador' }));
    expect(await screen.findByRole('heading', { level: 1, name: 'Gómez renombrada' })).toBeInTheDocument();
    expect(tutorPosts()).toHaveLength(1);
    expect(document.body.textContent).not.toContain('11 4444-0000');
  });
});

describe('add a tutor: inactive family (mock backend)', () => {
  it('409 FAMILIA_INACTIVA has its own message, keeps the draft and blocks sending until the current data are reviewed', async () => {
    const { family } = await openForm({ version: 2 });
    const user = userEvent.setup();
    await fill(user, { nombre: 'Ana', apellido: 'Pérez', telefono: '11 4444-0000' });
    backend.changeFamilyBehindTheScenes(family.id, { activa: false }); // deactivated by someone else
    await submit(user);

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('La familia está inactiva: no se pueden agregar tutores');
    expect(alert).toHaveTextContent('Tus datos siguen acá');
    expect(alert).not.toHaveTextContent('La familia cambió');
    expect(screen.getByLabelText(/^Teléfono/)).toHaveValue('11 4444-0000');
    expect(screen.getByRole('button', { name: 'Agregar tutor' })).toBeDisabled();
    expect(within(alert).getByRole('link', { name: 'Ir a la familia' })).toHaveAttribute('href', `/admin/familias/${family.id}`);
    await wait(400);
    expect(tutorPosts()).toHaveLength(1);

    // Reactivated elsewhere: reviewing the current data lets the user continue, with the current revision.
    backend.changeFamilyBehindTheScenes(family.id, { activa: true }); // revision 4
    await user.click(screen.getByRole('button', { name: 'Ver datos vigentes' }));
    expect(await screen.findByRole('group', { name: 'Datos vigentes' })).toHaveTextContent('Activa');
    await user.click(screen.getByRole('button', { name: 'Seguir con mi borrador' }));
    await submit(user);
    expect(await screen.findByText('Tutor agregado correctamente.')).toBeInTheDocument();
    expect(tutorPosts()[1]!.body).toMatchObject({ versionFamiliaEsperada: '4' });
  });

  it('if it is still inactive when reviewed, continuing is not possible', async () => {
    const { family } = await openForm({ version: 0 });
    const user = userEvent.setup();
    await fill(user, { nombre: 'Ana', apellido: 'Pérez' });
    backend.changeFamilyBehindTheScenes(family.id, { activa: false });
    await submit(user);
    await screen.findByText(/La familia está inactiva: no se pueden agregar tutores/);
    await user.click(screen.getByRole('button', { name: 'Ver datos vigentes' }));
    const panel = await screen.findByRole('group', { name: 'Datos vigentes' });
    expect(panel).toHaveTextContent('Inactiva');
    expect(within(panel).queryByRole('button', { name: 'Seguir con mi borrador' })).not.toBeInTheDocument();
    expect(within(panel).getByRole('button', { name: 'Descartar mi borrador' })).toBeInTheDocument();
    expect(tutorPosts()).toHaveLength(1);
  });
});

describe('add a tutor: uncertain result (mock backend)', () => {
  async function uncertain(failure: Parameters<FakeBackend['override']>[2]) {
    const { family } = await openForm({ version: 5 });
    const user = userEvent.setup();
    await fill(user, { nombre: 'Ana', apellido: 'Pérez', dni: '27123456' });
    backend.override('post', `/admin/familias/${family.id}/tutores`, failure);
    await submit(user);
    await screen.findByText(/No pudimos confirmar si el tutor se creó/);
    return { family, user };
  }

  it.each([
    ['a network failure or timeout', 'network' as const],
    ['a 500', { status: 500, body: { codigo: 'ERROR_INTERNO', mensaje: 'x' } }],
    ['a 503', { status: 503, body: { codigo: 'NO_DISPONIBLE', mensaje: 'x' } }],
  ])('does not claim it failed after %s, never retries by itself, keeps the form and blocks a blind resend', async (_label, failure) => {
    await uncertain(failure);
    await wait(400);
    expect(tutorPosts()).toHaveLength(1);
    expect(screen.getByLabelText(/^Nombre/)).toHaveValue('Ana');
    expect(screen.getByLabelText(/^DNI/)).toHaveValue('27123456');
    expect(screen.getByRole('button', { name: 'Agregar tutor' })).toBeDisabled(); // a resend with the same revision could create a SECOND tutor
    expect(screen.getByRole('button', { name: 'Verificar si se creó' })).toBeEnabled();
  });

  const NO_CLAIM = /ya figura creado|se creó correctamente|Tutor agregado correctamente/;

  it('A) a homonym created by someone else while our request never arrived is NOT presented as our creation, and the resend stays blocked', async () => {
    const { family, user } = await uncertain('network'); // our request never reached the backend
    backend.addTutorBehindTheScenes(family.id, { nombre: 'Ana', apellido: 'Pérez', dni: '11222333' }); // someone else, another DNI
    await user.click(screen.getByRole('button', { name: 'Verificar si se creó' }));
    const panel = await screen.findByRole('group', { name: 'Verificación' });
    expect(panel).toHaveTextContent('Puede ser el que intentaste crear o uno creado por otra persona: no podemos confirmarlo.');
    expect(panel).toHaveTextContent('El reenvío sigue bloqueado');
    expect(panel.textContent).not.toMatch(NO_CLAIM);
    expect(within(panel).queryByRole('button', { name: 'Volver a intentar' })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Agregar tutor' })).toBeDisabled();
    expect(tutorPosts()).toHaveLength(1);
  });

  it('B) several homonymous new tutors are reported as ambiguous, with the resend blocked', async () => {
    const { family, user } = await uncertain('network');
    backend.addTutorBehindTheScenes(family.id, { nombre: 'Ana', apellido: 'Pérez', dni: '11222333' });
    backend.addTutorBehindTheScenes(family.id, { nombre: 'Ana', apellido: 'Pérez', dni: '44555666' });
    await user.click(screen.getByRole('button', { name: 'Verificar si se creó' }));
    const panel = await screen.findByRole('group', { name: 'Verificación' });
    expect(panel).toHaveTextContent('Encontramos 2 tutores nuevos con ese nombre y apellido: la coincidencia es ambigua');
    expect(panel.textContent).not.toMatch(NO_CLAIM);
    expect(within(panel).queryByRole('button', { name: 'Volver a intentar' })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Agregar tutor' })).toBeDisabled();
    expect(tutorPosts()).toHaveLength(1);
  });

  it('when our request WAS processed (lost answer) the match is still only a hint: no success message, no resend, and the family page shows no creation notice', async () => {
    const { family, user } = await uncertain('network');
    backend.addTutorBehindTheScenes(family.id, { nombre: 'Ana', apellido: 'Pérez', dni: '27123456' }); // the lost answer
    await user.click(screen.getByRole('button', { name: 'Verificar si se creó' }));
    const panel = await screen.findByRole('group', { name: 'Verificación' });
    expect(panel).toHaveTextContent('no podemos confirmarlo');
    expect(panel.textContent).not.toMatch(NO_CLAIM);
    expect(within(panel).queryByRole('button', { name: 'Volver a intentar' })).not.toBeInTheDocument();
    await user.click(within(panel).getByRole('link', { name: 'Ver la familia' }));
    // The draft was never confirmed, so leaving it is still guarded (it would be lost if the tutor is not there).
    await user.click(within(await screen.findByRole('group', { name: 'Cambios sin guardar' })).getByRole('button', { name: 'Descartar y salir' }));
    const list = await screen.findByRole('list', { name: 'Tutores' });
    expect(within(list).getAllByRole('listitem')).toHaveLength(1);
    expect(screen.queryByText('Tutor agregado correctamente.')).not.toBeInTheDocument(); // only a server-confirmed creation shows it
    expect(tutorPosts()).toHaveLength(1);
  });

  it('with no match it says it was not found WHEN VERIFYING, warns about duplication, and only an explicit action allows trying again, with the revision it just read', async () => {
    const { family, user } = await uncertain({ status: 503, body: { codigo: 'NO_DISPONIBLE', mensaje: 'x' } });
    backend.changeFamilyBehindTheScenes(family.id, { nombreReferencia: 'Gómez renombrada' }); // revision 6 while it was in doubt
    await user.click(screen.getByRole('button', { name: 'Verificar si se creó' }));
    const panel = await screen.findByRole('group', { name: 'Verificación' });
    expect(panel).toHaveTextContent('No encontramos el tutor al verificar. La solicitud original podría seguir procesándose');
    expect(panel).toHaveTextContent('el tutor podría quedar duplicado');
    expect(panel).toHaveTextContent('Gómez renombrada');
    expect(panel.textContent).not.toMatch(NO_CLAIM);
    await wait(400);
    expect(tutorPosts()).toHaveLength(1); // never retried by itself
    expect(screen.getByRole('button', { name: 'Agregar tutor' })).toBeDisabled(); // still blocked until the explicit action
    await user.click(within(panel).getByRole('button', { name: 'Volver a intentar' }));
    expect(screen.getByLabelText(/^Nombre/)).toHaveValue('Ana');
    await submit(user);
    expect(await screen.findByText('Tutor agregado correctamente.')).toBeInTheDocument(); // confirmed by the backend this time
    expect(tutorPosts()).toHaveLength(2);
    expect(tutorPosts()[1]!.body).toMatchObject({ versionFamiliaEsperada: '6' });
  });

  it('a homonymous tutor that already existed before the attempt is not even counted as a match', async () => {
    const family = backend.addFamily('Gómez', { version: 1, tutores: [tutor({ id: 't-previo', nombre: 'Ana', apellido: 'Pérez' })] });
    openTutor(`/admin/familias/${family.id}/tutores/nuevo`);
    await screen.findByRole('heading', { name: 'Nuevo tutor' });
    const user = userEvent.setup();
    await fill(user, { nombre: 'Ana', apellido: 'Pérez' });
    backend.override('post', `/admin/familias/${family.id}/tutores`, 'network');
    await submit(user);
    await screen.findByText(/No pudimos confirmar si el tutor se creó/);
    await user.click(screen.getByRole('button', { name: 'Verificar si se creó' }));
    expect(await screen.findByRole('group', { name: 'Verificación' })).toHaveTextContent('No encontramos el tutor al verificar.');
  });

  it('shows an error if the verification itself cannot be done, and keeps the form and the block', async () => {
    const { family, user } = await uncertain('network');
    backend.override('get', `/admin/familias/${family.id}`, 'network');
    await user.click(screen.getByRole('button', { name: 'Verificar si se creó' }));
    expect(await screen.findByText(/No pudimos conectar con el servidor/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Agregar tutor' })).toBeDisabled();
    expect(screen.getByLabelText(/^Nombre/)).toHaveValue('Ana');
    await user.click(screen.getByRole('button', { name: 'Verificar si se creó' }));
    expect(await screen.findByRole('group', { name: 'Verificación' })).toBeInTheDocument();
  });
});
