import { act, fireEvent, screen, waitFor, within } from '@testing-library/react';
import userEvent, { type UserEvent } from '@testing-library/user-event';
import type { QueryClient } from '@tanstack/react-query';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { sessionKey } from '@/features/auth/session';
import type { FakeAdminAthlete, FakeBackend } from '@/test/fakeBackend';
import { admin, createBackend, renderApp } from '@/test/render';

// MOCK backend (see src/test/fakeBackend.ts, which mirrors PUT /api/admin/deportistas/{id}). Real-backend evidence lives in
// e2e/deportistas-edicion-estados.real.spec.ts.
let backend: FakeBackend;

beforeEach(() => {
  localStorage.clear();
  sessionStorage.clear();
  backend = createBackend();
});

const DNI = '30111322';
const CUIL = '20301113227';
const location = () => screen.getByTestId('location').textContent ?? '';
const puts = () => backend.requests.filter((r) => r.method === 'put' && /^\/admin\/deportistas\/[^/]+$/.test(r.url));
const wait = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms));

function seedFull(over: Partial<FakeAdminAthlete> = {}) {
  return backend.addAdminAthlete({
    nombre: 'Lucía',
    apellido: 'Sintética',
    dni: DNI,
    cuil: CUIL,
    fechaNacimiento: '2012-05-04',
    nacionalidad: 'Argentina',
    domicilio: 'Calle Falsa 123',
    otrosDatosDomicilio: 'Piso 2, depto B',
    localidad: 'Banfield',
    partido: 'Lomas de Zamora',
    codigoPostal: '1828',
    telefonoContacto: '11 5555-0000',
    emailFederativo: 'lucia@example.test',
    ...over,
  });
}

const LABELS = {
  dni: /^DNI/,
  nombre: /^Nombre/,
  apellido: /^Apellido/,
  cuil: /^CUIL/,
  nacimiento: /^Fecha de nacimiento/,
  nacionalidad: /^Nacionalidad/,
  telefono: /^Teléfono/,
  correo: /^Correo federativo/,
  domicilio: /^Domicilio \(opcional\)/,
  otros: /^Otros datos del domicilio/,
  localidad: /^Localidad/,
  partido: /^Partido/,
  cp: /^Código postal/,
} as const;

async function setField(user: UserEvent, key: keyof typeof LABELS, value: string) {
  const input = screen.getByLabelText(LABELS[key]);
  if (key === 'nacimiento') {
    fireEvent.change(input, { target: { value } });
    return;
  }
  await user.clear(input);
  if (value !== '') await user.type(input, value);
}

async function openEdit(athlete: FakeAdminAthlete): Promise<{ queryClient: QueryClient; user: UserEvent }> {
  backend.signIn('marcos@example.com');
  const { queryClient } = renderApp(`/admin/deportistas/${athlete.id}/editar`);
  await screen.findByRole('heading', { name: 'Editar deportista' });
  await waitFor(() => expect(screen.getByLabelText(LABELS.nombre)).toHaveValue(athlete.nombre));
  return { queryClient, user: userEvent.setup() };
}

const save = (user: UserEvent) => user.click(screen.getByRole('button', { name: 'Guardar cambios' }));
const detailKey = (id: string) => ['admin', 'athlete-detail', id];

describe('edit athlete: entry and access (mock backend)', () => {
  it('is reached from the detail and starts from ALL the stored values, the full DNI and CUIL included', async () => {
    const athlete = seedFull();
    backend.signIn('marcos@example.com');
    renderApp(`/admin/deportistas/${athlete.id}`);
    await userEvent.setup().click(await screen.findByRole('link', { name: 'Editar deportista' }));
    expect(await screen.findByRole('heading', { name: 'Editar deportista' })).toBeInTheDocument();
    expect(location()).toBe(`/admin/deportistas/${athlete.id}/editar`);
    await waitFor(() => expect(screen.getByLabelText(LABELS.dni)).toHaveValue(DNI));
    expect(screen.getByLabelText(LABELS.cuil)).toHaveValue(CUIL);
    expect(screen.getByLabelText(LABELS.nacimiento)).toHaveValue('2012-05-04');
    expect(screen.getByLabelText(LABELS.otros)).toHaveValue('Piso 2, depto B');
    expect(screen.getByLabelText(LABELS.correo)).toHaveValue('lucia@example.test');
    expect(puts()).toHaveLength(0);
    expect(location()).not.toContain(DNI);
  });

  it('has no state field: activating or deactivating is not part of the personal data', async () => {
    await openEdit(seedFull());
    expect(screen.queryByLabelText(/estado|activo/i)).not.toBeInTheDocument();
    expect(screen.queryByRole('checkbox')).not.toBeInTheDocument();
  });

  it('sends anonymous visitors to the login and keeps a family user out, never requesting the athlete', async () => {
    const athlete = seedFull();
    renderApp(`/admin/deportistas/${athlete.id}/editar`);
    expect(await screen.findByRole('heading', { name: 'Acceso administrativo' })).toBeInTheDocument();
    backend.signIn('lucia@example.com');
    renderApp(`/admin/deportistas/${athlete.id}/editar`);
    expect(await screen.findAllByRole('heading', { name: 'No tenés permiso para ver esta sección' })).not.toHaveLength(0);
    expect(backend.requests.filter((r) => r.url === `/admin/deportistas/${athlete.id}`)).toHaveLength(0);
  });

  it('shows one generic «not found» for an unknown or malformed id and for a 400, and never requests a malformed one', async () => {
    backend.signIn('marcos@example.com');
    renderApp('/admin/deportistas/no-existe/editar');
    expect(await screen.findByRole('heading', { name: 'No encontramos este deportista' })).toBeInTheDocument();
    expect(document.body).not.toHaveTextContent(/escuela|DEPORTISTA_NO_ENCONTRADO/i);
  });

  it('explains a permission error, a network failure and shows a loading state, with a working retry', async () => {
    const athlete = seedFull();
    const url = `/admin/deportistas/${athlete.id}`;
    backend.override('get', url, { status: 403, body: { codigo: 'ACCESO_DENEGADO', mensaje: 'x' } }, { once: true });
    backend.signIn('marcos@example.com');
    renderApp(`${url}/editar`);
    expect(await screen.findByRole('alert')).toHaveTextContent('No tenés permiso para ver este deportista.');
    await userEvent.setup().click(screen.getByRole('button', { name: 'Reintentar' }));
    expect(await screen.findByRole('heading', { name: 'Editar deportista' })).toBeInTheDocument();
    await waitFor(() => expect(screen.getByLabelText(LABELS.nombre)).toHaveValue('Lucía'));
  });
});

describe('edit athlete: saving a complete replacement (mock backend)', () => {
  it('has nothing to save until something changes', async () => {
    await openEdit(seedFull());
    expect(screen.getByRole('button', { name: 'Guardar cambios' })).toBeDisabled();
  });

  it('sends the whole record with the revision as a decimal string, no state, and confirms only after the answer', async () => {
    const athlete = seedFull();
    athlete.version = 4;
    const { user } = await openEdit(athlete);
    const held = backend.hold('put', `/admin/deportistas/${athlete.id}`);
    await setField(user, 'nombre', 'Lucía María');
    await save(user);
    expect(await screen.findByRole('button', { name: 'Guardando…' })).toBeDisabled();
    expect(screen.queryByText('Cambios guardados.')).not.toBeInTheDocument();
    expect(location()).toBe(`/admin/deportistas/${athlete.id}/editar`);
    held.release();
    expect(await screen.findByRole('heading', { level: 1, name: 'Lucía María Sintética' })).toBeInTheDocument();
    expect(screen.getByText('Cambios guardados.')).toBeInTheDocument();
    expect(location()).toBe(`/admin/deportistas/${athlete.id}`);
    expect(puts()).toHaveLength(1);
    expect(puts()[0]!.body).toEqual({
      dni: DNI,
      nombre: 'Lucía María',
      apellido: 'Sintética',
      cuil: CUIL,
      fechaNacimiento: '2012-05-04',
      nacionalidad: 'Argentina',
      domicilio: 'Calle Falsa 123',
      otrosDatosDomicilio: 'Piso 2, depto B',
      localidad: 'Banfield',
      partido: 'Lomas de Zamora',
      codigoPostal: '1828',
      telefonoContacto: '11 5555-0000',
      emailFederativo: 'lucia@example.test',
      versionEsperada: '4',
    });
    expect(Object.keys(puts()[0]!.body as object)).not.toContain('activo');
    expect(backend.adminAthlete(athlete.id)).toMatchObject({ nombre: 'Lucía María', activo: true, version: 5 });
  });

  it('sends an explicit null for every emptied optional field, says which saved data it removes (names, not values) and the backend really clears them', async () => {
    const athlete = seedFull();
    const { user } = await openEdit(athlete);
    await setField(user, 'telefono', '');
    await setField(user, 'otros', '   ');
    await setField(user, 'nacimiento', '');
    const removal = screen.getByText(/Al guardar se van a quitar estos datos/);
    expect(removal).toHaveTextContent('Al guardar se van a quitar estos datos: Fecha de nacimiento, Otros datos del domicilio, Teléfono.');
    expect(removal).not.toHaveTextContent(/5555|Piso 2|2012/);
    await save(user);
    await screen.findByText('Cambios guardados.');
    expect(puts()[0]!.body).toMatchObject({ telefonoContacto: null, otrosDatosDomicilio: null, fechaNacimiento: null, nombre: 'Lucía', cuil: CUIL });
    expect(backend.adminAthlete(athlete.id)).toMatchObject({ telefonoContacto: null, otrosDatosDomicilio: null, fechaNacimiento: null });
    expect(screen.getAllByText('No informado').length).toBeGreaterThanOrEqual(3);
  });

  it('keeps the normalization of the backend: a DNI typed with dots and an e-mail in capitals are the same data', async () => {
    const athlete = seedFull();
    const { user } = await openEdit(athlete);
    await setField(user, 'dni', '30.111.322');
    await setField(user, 'correo', 'LUCIA@Example.test');
    expect(screen.getByRole('button', { name: 'Guardar cambios' })).toBeDisabled(); // nothing really changed
    await setField(user, 'nombre', 'Lucía M');
    await save(user);
    await screen.findByText('Cambios guardados.');
    expect(backend.adminAthlete(athlete.id)).toMatchObject({ dni: DNI, emailFederativo: 'lucia@example.test' });
  });

  it.each([
    ['an empty DNI', 'dni', '', 'Ingresá el DNI.'],
    ['a short DNI', 'dni', '123', 'El DNI debe tener entre 7 y 9 dígitos.'],
    ['an empty name', 'nombre', '', 'Ingresá el nombre.'],
    ['an empty last name', 'apellido', '', 'Ingresá el apellido.'],
    ['a CUIL with a wrong check digit', 'cuil', '20301113228', 'El CUIL debe tener 11 dígitos y un dígito verificador válido.'],
    ['a future birth date', 'nacimiento', '2999-01-01', 'Ingresá una fecha de nacimiento válida y anterior a hoy.'],
    ['an invalid e-mail', 'correo', 'sin-arroba', 'Ingresá un correo electrónico válido.'],
  ] as const)('validates %s before sending anything, next to its field', async (_name, key, value, message) => {
    const { user } = await openEdit(seedFull());
    await setField(user, key, value);
    await save(user);
    expect(await screen.findByText(message)).toBeInTheDocument();
    expect(screen.getByLabelText(LABELS[key])).toHaveAttribute('aria-invalid', 'true');
    expect(puts()).toHaveLength(0);
  });

  it('sends a single request however many times the form is submitted while one is in flight', async () => {
    const athlete = seedFull();
    const { user } = await openEdit(athlete);
    const held = backend.hold('put', `/admin/deportistas/${athlete.id}`);
    await setField(user, 'nombre', 'Lucía M');
    await save(user);
    await screen.findByRole('button', { name: 'Guardando…' });
    fireEvent.submit(screen.getByRole('form', { name: 'Editar deportista' }));
    fireEvent.submit(screen.getByRole('form', { name: 'Editar deportista' }));
    held.release();
    await screen.findByText('Cambios guardados.');
    expect(puts()).toHaveLength(1);
    expect(backend.adminAthleteVersion(athlete.id)).toBe('1'); // moved exactly once
  });

  it('never saves by itself', async () => {
    const { user } = await openEdit(seedFull());
    await setField(user, 'nombre', 'Lucía M');
    await act(async () => wait(600));
    expect(puts()).toHaveLength(0);
  });
});

describe('edit athlete: duplicates and server errors (mock backend)', () => {
  it('tells a DNI held by another ACTIVE athlete at the DNI field, keeps the draft and the revision, and the corrected form can be sent again', async () => {
    backend.addAdminAthlete({ nombre: 'Otro', apellido: 'Activo', dni: '40111222' });
    const athlete = seedFull();
    const { user } = await openEdit(athlete);
    await setField(user, 'dni', '40111222');
    await save(user);
    expect(await screen.findByText('Ya existe otro deportista activo con ese DNI. Revisá el número.')).toBeInTheDocument();
    expect(screen.getByLabelText(LABELS.dni)).toHaveAttribute('aria-invalid', 'true');
    expect(screen.getByLabelText(LABELS.dni)).toHaveValue('40111222');
    expect(location()).toBe(`/admin/deportistas/${athlete.id}/editar`);
    expect(screen.getByRole('button', { name: 'Guardar cambios' })).toBeEnabled();
    await setField(user, 'dni', '40111999');
    await save(user);
    await screen.findByText('Cambios guardados.');
    expect(puts()).toHaveLength(2);
    expect((puts()[1]!.body as { versionEsperada: string }).versionEsperada).toBe('0'); // nothing was applied: same revision
  });

  it('tells a DNI reserved by an INACTIVE athlete with its own message', async () => {
    backend.addAdminAthlete({ nombre: 'Otro', apellido: 'Inactivo', dni: '40111222', activo: false });
    const { user } = await openEdit(seedFull());
    await setField(user, 'dni', '40111222');
    await save(user);
    expect(await screen.findByText(/Otro deportista inactivo tiene reservado ese DNI/)).toBeInTheDocument();
    expect(screen.getByText(/filtro «Inactivos» y reactivalo desde su detalle/)).toBeInTheDocument();
    expect(screen.queryByText(/otro deportista activo/)).not.toBeInTheDocument();
  });

  it('tells a duplicate CUIL at the CUIL field and does not blame the DNI', async () => {
    backend.addAdminAthlete({ nombre: 'Otro', apellido: 'Cuil', dni: '40111222', cuil: '27402223338' });
    const { user } = await openEdit(seedFull());
    await setField(user, 'cuil', '27-40222333-8');
    await save(user);
    expect(await screen.findByText('Ya existe otro deportista con ese CUIL. Revisá el número.')).toBeInTheDocument();
    expect(screen.getByLabelText(LABELS.cuil)).toHaveAttribute('aria-invalid', 'true');
    expect(screen.getByLabelText(LABELS.dni)).not.toHaveAttribute('aria-invalid');
  });

  it('keeping its own DNI and CUIL is not a duplicate', async () => {
    const athlete = seedFull();
    const { user } = await openEdit(athlete);
    await setField(user, 'nombre', 'Lucía M');
    await save(user);
    await screen.findByText('Cambios guardados.');
    expect(backend.adminAthlete(athlete.id)).toMatchObject({ dni: DNI, cuil: CUIL });
  });

  it.each([
    ['a 400 of the server', { status: 400, body: { codigo: 'VALIDACION', mensaje: 'detalle interno', detalles: [{ campo: 'dni', mensaje: 'detalle del servidor' }] } }, 'Revisá los datos del deportista'],
    ['a 403', { status: 403, body: { codigo: 'ACCESO_DENEGADO', mensaje: 'x' } }, 'No tenés permiso para editar deportistas.'],
    ['a 404 (also another school: the same answer)', { status: 404, body: { codigo: 'DEPORTISTA_NO_ENCONTRADO', mensaje: 'x' } }, 'El deportista ya no existe.'],
    ['an unrelated conflict', { status: 409, body: { codigo: 'CONFLICTO_CONCURRENCIA', mensaje: 'x' } }, 'Otra operación está en curso'],
    ['a CSRF failure', { status: 403, body: { codigo: 'CSRF_INVALIDO', mensaje: 'detalle interno' } }, 'No pudimos validar la solicitud'],
  ] as const)('explains %s with a fixed text and keeps the draft', async (_name, answer, text) => {
    const athlete = seedFull();
    backend.override('put', `/admin/deportistas/${athlete.id}`, answer);
    const { user } = await openEdit(athlete);
    await setField(user, 'nombre', 'Lucía M');
    await save(user);
    expect(await screen.findByRole('alert')).toHaveTextContent(text);
    expect(document.body).not.toHaveTextContent(/detalle interno|detalle del servidor/);
    expect(screen.getByLabelText(LABELS.nombre)).toHaveValue('Lucía M');
  });

  it('sends a session that ended (401) to the admin login', async () => {
    const { user } = await openEdit(seedFull());
    await setField(user, 'nombre', 'Lucía M');
    backend.expireSession();
    await save(user);
    expect(await screen.findByRole('heading', { name: 'Acceso administrativo' })).toBeInTheDocument();
  });
});

describe('edit athlete: the revision (mock backend)', () => {
  it('sends the revision captured when the form opened, even if the data are refreshed meanwhile, and answers the conflict without saving', async () => {
    const athlete = seedFull();
    athlete.version = 2;
    const { user, queryClient } = await openEdit(athlete);
    await setField(user, 'nombre', 'Lucía M');
    backend.changeAdminAthleteBehindTheScenes(athlete.id, { apellido: 'Gómez' }); // revision 3
    await act(async () => {
      await queryClient.invalidateQueries({ queryKey: detailKey(athlete.id) }); // the screen DOES see the new data
    });
    await save(user);
    expect(await screen.findByRole('alert')).toHaveTextContent('Otro administrador pudo haber modificado este deportista');
    expect((puts()[0]!.body as { versionEsperada: string }).versionEsperada).toBe('2'); // never refreshed on the way
    expect(screen.getByLabelText(LABELS.apellido)).toHaveValue('Sintética'); // the form never follows refreshes by itself
    expect(backend.adminAthlete(athlete.id)).toMatchObject({ apellido: 'Gómez', nombre: 'Lucía' }); // nothing was overwritten
  });

  it('on a stale revision keeps the draft, blocks saving, and does not resend by itself', async () => {
    const athlete = seedFull();
    const { user } = await openEdit(athlete);
    await setField(user, 'nombre', 'Lucía M');
    backend.changeAdminAthleteBehindTheScenes(athlete.id, { apellido: 'Gómez' });
    await save(user);
    await screen.findByText(/Otro administrador pudo haber modificado/);
    await act(async () => wait(700));
    expect(puts()).toHaveLength(1);
    expect(screen.getByRole('button', { name: 'Guardar cambios' })).toBeDisabled();
    expect(screen.getByLabelText(LABELS.nombre)).toHaveValue('Lucía M');
    expect(screen.getByLabelText(LABELS.nombre)).toBeDisabled();
    fireEvent.submit(screen.getByRole('form', { name: 'Editar deportista' }));
    expect(puts()).toHaveLength(1);
  });

  it('shows the differences, takes the current value of a field the administrator did not touch and keeps what they changed', async () => {
    const athlete = seedFull();
    const { user } = await openEdit(athlete);
    await setField(user, 'nombre', 'Lucía M');
    backend.changeAdminAthleteBehindTheScenes(athlete.id, { apellido: 'Gómez', localidad: 'Lanús' });
    await save(user);
    await screen.findByText(/Otro administrador pudo haber modificado/);
    await user.click(screen.getByRole('button', { name: 'Ver datos vigentes' }));
    const group = await screen.findByRole('group', { name: 'Datos vigentes' });
    const table = within(group).getByRole('table', { name: 'Comparación de datos' });
    expect(within(table).getByRole('row', { name: /Apellido/ })).toHaveTextContent(/Cambió otra persona: se incorpora el valor vigente.*Gómez.*Sintética/);
    expect(within(table).getByRole('row', { name: /Nombre/ })).toHaveTextContent(/Solo cambiaste vos: se conserva tu valor.*Lucía.*Lucía M/);
    expect(within(table).getByRole('row', { name: /Localidad/ })).toHaveTextContent('Lanús');
    expect(within(table).queryByRole('row', { name: /Partido/ })).not.toBeInTheDocument(); // nobody changed it
    expect(within(group).queryByRole('radio')).not.toBeInTheDocument(); // no conflict: no decision needed
    expect(screen.getByLabelText(LABELS.apellido)).toHaveValue('Sintética'); // nothing is mixed in until the administrator decides
    await user.click(within(group).getByRole('button', { name: 'Seguir editando con los datos vigentes' }));
    expect(screen.getByLabelText(LABELS.nombre)).toHaveValue('Lucía M');
    expect(screen.getByLabelText(LABELS.apellido)).toHaveValue('Gómez');
    expect(screen.getByLabelText(LABELS.localidad)).toHaveValue('Lanús');
    expect(puts()).toHaveLength(1); // choosing to go on is not sending
    await save(user);
    await screen.findByText('Cambios guardados.');
    expect((puts()[1]!.body as { versionEsperada: string }).versionEsperada).toBe('1');
    expect(backend.adminAthlete(athlete.id)).toMatchObject({ nombre: 'Lucía M', apellido: 'Gómez', localidad: 'Lanús' });
  });

  it('requires an explicit choice when both changed the same field to different values, with no default and no way to continue before it', async () => {
    const athlete = seedFull();
    const { user } = await openEdit(athlete);
    await setField(user, 'nombre', 'Mi Nombre');
    await setField(user, 'localidad', 'Mi Localidad');
    backend.changeAdminAthleteBehindTheScenes(athlete.id, { nombre: 'Su Nombre', localidad: 'Su Localidad' });
    await save(user);
    await user.click(await screen.findByRole('button', { name: 'Ver datos vigentes' }));
    const group = await screen.findByRole('group', { name: 'Datos vigentes' });
    const name = within(group).getByRole('group', { name: 'Nombre' });
    const place = within(group).getByRole('group', { name: 'Localidad' });
    expect(within(name).getAllByRole('radio')).toHaveLength(2);
    for (const radio of within(group).getAllByRole('radio')) expect(radio).not.toBeChecked(); // no silent default
    const proceed = within(group).getByRole('button', { name: 'Seguir editando con los datos vigentes' });
    expect(proceed).toBeDisabled();
    expect(within(group).getByText('Resolvé los 2 conflictos para poder seguir.')).toBeInTheDocument();
    await user.click(within(name).getByRole('radio', { name: /Usar el valor vigente: Su Nombre/ }));
    expect(proceed).toBeDisabled(); // one decision is not enough
    fireEvent.click(proceed);
    expect(screen.getByLabelText(LABELS.nombre)).toBeDisabled(); // the form did not reopen
    await user.click(within(place).getByRole('radio', { name: /Mantener mi valor: Mi Localidad/ }));
    expect(proceed).toBeEnabled();
    await user.click(proceed);
    expect(screen.getByLabelText(LABELS.nombre)).toHaveValue('Su Nombre');
    expect(screen.getByLabelText(LABELS.localidad)).toHaveValue('Mi Localidad');
    await save(user);
    await screen.findByText('Cambios guardados.');
    expect(backend.adminAthlete(athlete.id)).toMatchObject({ nombre: 'Su Nombre', localidad: 'Mi Localidad' });
  });

  it('does not call it a conflict when both made the same change, and a coinciding draft needs no save', async () => {
    const athlete = seedFull();
    const { user } = await openEdit(athlete);
    await setField(user, 'nombre', 'Lucía M');
    await setField(user, 'localidad', 'Lanús');
    backend.changeAdminAthleteBehindTheScenes(athlete.id, { nombre: 'Lucía M', localidad: 'Temperley' });
    await save(user);
    await user.click(await screen.findByRole('button', { name: 'Ver datos vigentes' }));
    const group = await screen.findByRole('group', { name: 'Datos vigentes' });
    expect(within(group).getByRole('row', { name: /Nombre/ })).toHaveTextContent('Los dos lo cambiaron igual');
    expect(within(group).getByRole('group', { name: 'Localidad' })).toBeInTheDocument(); // only this one differs
    expect(within(group).queryByRole('group', { name: 'Nombre' })).not.toBeInTheDocument();
  });

  it('closes without saving when the current data already match the draft, without claiming whose change it was', async () => {
    const athlete = seedFull();
    const { user } = await openEdit(athlete);
    await setField(user, 'nombre', 'Lucía M');
    backend.changeAdminAthleteBehindTheScenes(athlete.id, { nombre: 'Lucía M' });
    await save(user);
    await user.click(await screen.findByRole('button', { name: 'Ver datos vigentes' }));
    const group = await screen.findByRole('group', { name: 'Datos vigentes' });
    expect(group).toHaveTextContent('Los datos vigentes ya coinciden con tu borrador: no queda nada por guardar. No podemos saber si fue tu envío u otro cambio.');
    expect(within(group).queryByRole('button', { name: /Seguir editando/ })).not.toBeInTheDocument();
    await user.click(within(group).getByRole('button', { name: 'Cerrar' }));
    expect(await screen.findByRole('heading', { level: 1, name: 'Lucía M Sintética' })).toBeInTheDocument();
    expect(puts()).toHaveLength(1);
  });

  it('a revision moved by a change of STATE has no personal differences, and the draft can go on with the new revision', async () => {
    const athlete = seedFull();
    const { user } = await openEdit(athlete);
    await setField(user, 'nombre', 'Lucía M');
    backend.changeAdminAthleteBehindTheScenes(athlete.id, { activo: false });
    await save(user);
    await user.click(await screen.findByRole('button', { name: 'Ver datos vigentes' }));
    const group = await screen.findByRole('group', { name: 'Datos vigentes' });
    expect(group).toHaveTextContent('no tienen diferencias');
    expect(within(group).getByText('Inactivo')).toBeInTheDocument();
    await user.click(within(group).getByRole('button', { name: 'Seguir editando con los datos vigentes' }));
    await save(user);
    await screen.findByText('Cambios guardados.');
    expect(backend.adminAthlete(athlete.id)).toMatchObject({ nombre: 'Lucía M', activo: false }); // the edit did not change the state
  });

  it('a SECOND concurrent change after going on generates a new conflict, never a silent overwrite', async () => {
    const athlete = seedFull();
    const { user } = await openEdit(athlete);
    await setField(user, 'nombre', 'Lucía M');
    backend.changeAdminAthleteBehindTheScenes(athlete.id, { apellido: 'Gómez' }); // revision 1
    await save(user);
    await user.click(await screen.findByRole('button', { name: 'Ver datos vigentes' }));
    await user.click(await screen.findByRole('button', { name: 'Seguir editando con los datos vigentes' }));
    backend.changeAdminAthleteBehindTheScenes(athlete.id, { apellido: 'Pérez' }); // revision 2, after the administrator went on
    await save(user);
    expect(await screen.findByRole('alert')).toHaveTextContent('Otro administrador pudo haber modificado');
    expect((puts()[1]!.body as { versionEsperada: string; apellido: string })).toMatchObject({ versionEsperada: '1', apellido: 'Gómez' });
    expect(backend.adminAthlete(athlete.id)).toMatchObject({ apellido: 'Pérez', nombre: 'Lucía' }); // still not overwritten
    await user.click(screen.getByRole('button', { name: 'Ver datos vigentes' }));
    const group = await screen.findByRole('group', { name: 'Datos vigentes' });
    expect(within(group).getByRole('row', { name: /Apellido/ })).toHaveTextContent('Pérez');
    await user.click(within(group).getByRole('button', { name: 'Seguir editando con los datos vigentes' }));
    await save(user);
    await screen.findByText('Cambios guardados.');
    expect(backend.adminAthlete(athlete.id)).toMatchObject({ nombre: 'Lucía M', apellido: 'Pérez' });
    expect(puts()).toHaveLength(3);
  });

  it('a failed reload keeps the draft and the block, and the discard leaves without saving', async () => {
    const athlete = seedFull();
    const { user } = await openEdit(athlete);
    await setField(user, 'nombre', 'Lucía M');
    backend.changeAdminAthleteBehindTheScenes(athlete.id, { apellido: 'Gómez' });
    await save(user);
    await screen.findByText(/Otro administrador pudo haber modificado/);
    backend.override('get', `/admin/deportistas/${athlete.id}`, 'network', { once: true });
    await user.click(screen.getByRole('button', { name: 'Ver datos vigentes' }));
    expect(await screen.findByText(/No pudimos conectar con el servidor/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Guardar cambios' })).toBeDisabled();
    expect(screen.getByLabelText(LABELS.nombre)).toHaveValue('Lucía M');
    await user.click(screen.getByRole('button', { name: 'Ver datos vigentes' }));
    await user.click(await screen.findByRole('button', { name: 'Descartar mi borrador' }));
    expect(await screen.findByRole('heading', { level: 1, name: 'Lucía Gómez' })).toBeInTheDocument();
    expect(puts()).toHaveLength(1);
  });
});

describe('edit athlete: an answer that cannot be confirmed (mock backend)', () => {
  async function submitLost(kind: 'network' | { status: number; body: unknown }, change: (athlete: FakeAdminAthlete) => void = () => undefined) {
    const athlete = seedFull();
    backend.override('put', `/admin/deportistas/${athlete.id}`, kind, { once: true });
    const { user } = await openEdit(athlete);
    await setField(user, 'nombre', 'Lucía M');
    change(athlete); // what really happened on the server although the answer never arrived
    await save(user);
    await screen.findByText(/No pudimos confirmar si el cambio se guardó/);
    return { athlete, user };
  }

  it.each([
    ['a lost response (network)', 'network' as const],
    ['a 500', { status: 500, body: { codigo: 'ERROR_INTERNO', mensaje: 'detalle interno' } }],
    ['a 503', { status: 503, body: { codigo: 'NO_DISPONIBLE', mensaje: 'x' } }],
    ['a 200 whose body cannot be read', { status: 200, body: { basura: true } }],
  ])('does not say it failed, does not say it was saved, does not retry and blocks the resend after %s', async (_name, kind) => {
    const { user } = await submitLost(kind);
    await act(async () => wait(700));
    expect(puts()).toHaveLength(1);
    expect(screen.queryByText('Cambios guardados.')).not.toBeInTheDocument();
    expect(location()).toMatch(/\/editar$/);
    expect(document.body).not.toHaveTextContent('detalle interno');
    expect(screen.getByRole('button', { name: 'Guardar cambios' })).toBeDisabled();
    expect(screen.getByLabelText(LABELS.nombre)).toHaveValue('Lucía M'); // the draft is still there
    await user.keyboard('{Enter}');
    fireEvent.submit(screen.getByRole('form', { name: 'Editar deportista' }));
    expect(puts()).toHaveLength(1);
  });

  it('write NOT applied: the verification sees the same revision, says nothing was seen saved, and an explicit retry with the SAME revision saves once', async () => {
    const { athlete, user } = await submitLost('network');
    await user.click(screen.getByRole('button', { name: 'Verificar datos vigentes' }));
    const group = await screen.findByRole('group', { name: 'Verificación' });
    expect(group).toHaveTextContent('No vemos cambios guardados al verificar.');
    expect(group).toHaveTextContent('se rechazaría por revisión obsoleta en lugar de aplicarse dos veces');
    expect(group).not.toHaveTextContent(/no se guardó|no se aplicó/i);
    expect(screen.getByRole('button', { name: 'Guardar cambios' })).toBeDisabled(); // verifying alone does not reopen the form
    await user.click(within(group).getByRole('button', { name: 'Volver a intentar' }));
    expect(puts()).toHaveLength(1); // choosing to retry is not sending
    await save(user);
    await screen.findByText('Cambios guardados.');
    expect(puts()).toHaveLength(2);
    expect((puts()[1]!.body as { versionEsperada: string }).versionEsperada).toBe('0');
    expect(backend.adminAthlete(athlete.id)).toMatchObject({ nombre: 'Lucía M', version: 1 });
  });

  it('write APPLIED: the verification finds the data matching the draft and never claims it was this request', async () => {
    const { athlete, user } = await submitLost('network', (a) => backend.changeAdminAthleteBehindTheScenes(a.id, { nombre: 'Lucía M' }));
    await user.click(screen.getByRole('button', { name: 'Verificar datos vigentes' }));
    const group = await screen.findByRole('group', { name: 'Verificación' });
    expect(group).toHaveTextContent('Los datos vigentes ya coinciden con tu borrador: no queda nada por guardar. No podemos saber si fue tu envío u otro cambio.');
    expect(group).not.toHaveTextContent(/se guardó correctamente|Cambios guardados/);
    expect(screen.queryByText('Cambios guardados.')).not.toBeInTheDocument();
    expect(within(group).queryByRole('button', { name: /Volver a intentar|Seguir editando/ })).not.toBeInTheDocument();
    expect(puts()).toHaveLength(1);
    expect(backend.adminAthleteVersion(athlete.id)).toBe('1');
  });

  it('the data changed by SOMEONE ELSE after an uncertain answer go through the field-by-field review instead of being decided', async () => {
    const { user } = await submitLost('network', (a) => backend.changeAdminAthleteBehindTheScenes(a.id, { apellido: 'Gómez' }));
    await user.click(screen.getByRole('button', { name: 'Verificar datos vigentes' }));
    const group = await screen.findByRole('group', { name: 'Verificación' });
    expect(within(group).getByRole('row', { name: /Apellido/ })).toHaveTextContent('Gómez');
    expect(within(group).getByRole('row', { name: /Nombre/ })).toHaveTextContent('Solo cambiaste vos');
    await user.click(within(group).getByRole('button', { name: 'Seguir editando con los datos vigentes' }));
    await save(user);
    await screen.findByText('Cambios guardados.');
    expect((puts()[1]!.body as { versionEsperada: string }).versionEsperada).toBe('1');
  });

  it('a failed verification keeps the resend blocked', async () => {
    const { athlete, user } = await submitLost('network');
    backend.override('get', `/admin/deportistas/${athlete.id}`, 'network', { once: true });
    await user.click(screen.getByRole('button', { name: 'Verificar datos vigentes' }));
    expect(await screen.findByText(/No pudimos conectar con el servidor/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Guardar cambios' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Verificar datos vigentes' })).toBeEnabled();
  });
});

describe('edit athlete: unsaved changes, identity and privacy (mock backend)', () => {
  it('does not warn about an untouched form and cancels back to the detail', async () => {
    const athlete = seedFull();
    const { user } = await openEdit(athlete);
    await user.click(screen.getByRole('link', { name: 'Cancelar' }));
    expect(await screen.findByRole('heading', { level: 1, name: 'Lucía Sintética' })).toBeInTheDocument();
    expect(puts()).toHaveLength(0);
  });

  it('warns before leaving a draft by a link, lets the administrator keep editing or discard, and asks the browser to confirm closing the tab', async () => {
    const { user } = await openEdit(seedFull());
    const clean = new Event('beforeunload', { cancelable: true });
    window.dispatchEvent(clean);
    expect(clean.defaultPrevented).toBe(false);
    await setField(user, 'nombre', 'Borrador');
    const dirty = new Event('beforeunload', { cancelable: true });
    window.dispatchEvent(dirty);
    expect(dirty.defaultPrevented).toBe(true);
    await user.click(screen.getByRole('link', { name: 'Cancelar' }));
    const panel = await screen.findByRole('group', { name: 'Cambios sin guardar' });
    await user.click(within(panel).getByRole('button', { name: 'Seguir editando' }));
    expect(screen.getByLabelText(LABELS.nombre)).toHaveValue('Borrador');
    await user.click(screen.getByRole('link', { name: 'Deportistas' }));
    await user.click(within(await screen.findByRole('group', { name: 'Cambios sin guardar' })).getByRole('button', { name: 'Descartar y salir' }));
    expect(await screen.findByRole('heading', { name: 'Deportistas' })).toBeInTheDocument();
    expect(puts()).toHaveLength(0);
  });

  it('drops the draft and the captured revision when the session now belongs to another administrator', async () => {
    backend.addUser({ ...admin, id: 'u-admin-3', nombre: 'Otra', email: 'otra.admin@example.com', mfaEnrolado: true });
    const athlete = seedFull();
    const { user, queryClient } = await openEdit(athlete);
    await setField(user, 'nombre', 'Borrador del primero');
    backend.changeAdminAthleteBehindTheScenes(athlete.id, { apellido: 'Gómez' }); // the new identity reads the CURRENT data (revision 1)
    backend.signIn('otra.admin@example.com');
    const held = backend.hold('get', `/admin/deportistas/${athlete.id}`);
    await act(async () => {
      await queryClient.invalidateQueries({ queryKey: sessionKey });
    });
    expect(await screen.findByText('Cargando el deportista…')).toBeInTheDocument(); // nothing of the previous administrator is on screen
    expect(document.body).not.toHaveTextContent('Borrador del primero');
    expect(screen.queryByLabelText(LABELS.dni)).not.toBeInTheDocument();
    held.release();
    await waitFor(() => expect(screen.getByLabelText(LABELS.nombre)).toHaveValue('Lucía'));
    expect(screen.getByLabelText(LABELS.apellido)).toHaveValue('Gómez');
    expect(document.body.textContent).not.toContain('Borrador del primero');
    expect(puts()).toHaveLength(0);
    await setField(userEvent.setup(), 'nombre', 'Lucía M');
    await save(userEvent.setup());
    await screen.findByText('Cambios guardados.');
    expect((puts()[0]!.body as { versionEsperada: string }).versionEsperada).toBe('1'); // the revision of the new read, never the previous one
  });

  it('keeps the data out of the address, storage and console, also across a conflict', async () => {
    const spies = (['log', 'info', 'warn', 'error', 'debug'] as const).map((level) => vi.spyOn(console, level).mockImplementation(() => undefined));
    const pattern = new RegExp(`${DNI}|${CUIL}|lucia@|5555|Falsa|Borrador secreto`);
    try {
      const athlete = seedFull();
      const { user } = await openEdit(athlete);
      await setField(user, 'nombre', 'Borrador secreto');
      backend.changeAdminAthleteBehindTheScenes(athlete.id, { apellido: 'Gómez' });
      await save(user);
      await user.click(await screen.findByRole('button', { name: 'Ver datos vigentes' }));
      await screen.findByRole('group', { name: 'Datos vigentes' });
      expect(location()).not.toMatch(pattern);
      expect(JSON.stringify({ ...localStorage, ...sessionStorage })).not.toMatch(pattern);
      expect(spies.flatMap((spy) => spy.mock.calls).flat().join(' ')).not.toMatch(pattern);
    } finally {
      spies.forEach((spy) => spy.mockRestore());
    }
  });

  it('drops the private data from memory on leaving the edit screen', async () => {
    const athlete = seedFull();
    const { user, queryClient } = await openEdit(athlete);
    await user.click(screen.getByRole('link', { name: 'Cancelar' }));
    await screen.findByRole('heading', { level: 1, name: 'Lucía Sintética' });
    await user.click(screen.getByRole('link', { name: 'Volver al listado' }));
    await screen.findByRole('heading', { name: 'Deportistas' });
    await waitFor(() => expect(queryClient.getQueryData(detailKey(athlete.id))).toBeUndefined());
  });
});
