import { cleanup, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import type { FakeBackend } from '@/test/fakeBackend';
import { admin, createBackend, family, renderApp } from '@/test/render';
import { ApiError } from '@/lib/api-error';
import { updateAdult } from './api';
import { classify } from './messages';

// MOCK backend (src/test/fakeBackend.ts mirrors AdultosFamiliaController). Real-backend evidence: e2e/adultos-familia.real.spec.ts.
let backend: FakeBackend;

beforeEach(() => {
  localStorage.clear();
  sessionStorage.clear();
  backend = createBackend();
  backend.signIn(family.email);
  backend.adultsSelfService.enabled = true;
});

afterEach(() => cleanup());

const writes = () => backend.requests.filter((r) => r.url.startsWith('/familia/tutores') && r.method !== 'get');
const list = () => screen.getByRole('list', { name: 'Adultos de tu familia' });

async function open() {
  renderApp('/familia/adultos');
  await screen.findByRole('heading', { level: 1, name: 'Otros adultos' });
  await waitFor(() => expect(screen.queryByText(/Cargando los adultos/)).not.toBeInTheDocument());
}

async function fillAdd(user: ReturnType<typeof userEvent.setup>, data: { nombre: string; apellido: string; dni?: string; parentesco?: string }) {
  await user.click(await screen.findByRole('button', { name: 'Agregar otro adulto' }));
  await user.type(screen.getByLabelText(/^Nombre/), data.nombre);
  await user.type(screen.getByLabelText(/^Apellido/), data.apellido);
  if (data.dni) await user.type(screen.getByLabelText(/^DNI/), data.dni);
  if (data.parentesco) await user.type(screen.getByLabelText(/^Parentesco/), data.parentesco);
}

describe('Otros adultos (mock backend)', () => {
  it('is reachable from the family dashboard and says that adding an adult gives no access', async () => {
    renderApp('/familia');
    const link = await screen.findByRole('link', { name: 'Ver otros adultos' });
    expect(link).toHaveAttribute('href', '/familia/adultos');
    expect(screen.getByText(/Esto no les da acceso a los deportistas/)).toBeInTheDocument();
    // Previous behaviour: «Mis datos» is still there.
    expect(screen.getByRole('link', { name: 'Ver mis datos' })).toHaveAttribute('href', '/familia/mis-datos');
  });

  it('lists only the adults of the own family, each state in plain words and the DNI only masked', async () => {
    backend.addPortalFamily('familia-2', 'Familia Otra');
    backend.addAdult('familia-1', { nombre: 'Ana', apellido: 'Pérez', dni: '30111222', estado: 'DECLARADO' });
    backend.addAdult('familia-1', { nombre: 'Beto', apellido: 'Sosa', estado: 'VERIFICADO', origen: 'ADMIN' });
    backend.addAdult('familia-1', { nombre: 'Carla', apellido: 'Ruiz', estado: 'OBSERVADO', motivoObservacion: 'Falta el segundo apellido.', hasAccount: true });
    backend.addAdult('familia-2', { nombre: 'Intrusa', apellido: 'Ajena' });
    await open();

    const items = within(list()).getAllByRole('listitem');
    expect(items).toHaveLength(3);
    expect(within(items[0]!).getByText('Pendiente de revisión del club')).toBeInTheDocument();
    expect(within(items[0]!).getByText('DNI:').nextSibling).toHaveTextContent('••••222');
    expect(within(items[1]!).getByText('Verificado por el club')).toBeInTheDocument();
    expect(within(items[1]!).getByText('Cargado por el club')).toBeInTheDocument();
    expect(within(items[2]!).getByText('El club pidió una corrección')).toBeInTheDocument();
    expect(within(items[2]!).getByText(/Falta el segundo apellido\./)).toBeInTheDocument();
    expect(within(items[2]!).getByText('Tiene su propia cuenta')).toBeInTheDocument();
    expect(screen.queryByText(/Intrusa/)).not.toBeInTheDocument();
    expect(document.body.textContent).not.toContain('30111222');
  });

  it('marks the own profile (edited from «Mis datos», PERFIL_PROPIO from here) and keeps an adult with their own account read-only', async () => {
    backend.addAdult('familia-1', { nombre: 'Lucía', apellido: 'Gómez', ownerUserId: family.id });
    backend.addAdult('familia-1', { nombre: 'Dora', apellido: 'Lima', hasAccount: true });
    await open();
    const [mine, other] = within(list()).getAllByRole('listitem') as [HTMLElement, HTMLElement];
    expect(within(mine).getByText('Sos vos')).toBeInTheDocument();
    expect(within(mine).queryByRole('button', { name: /Editar/ })).not.toBeInTheDocument();
    expect(within(mine).getByRole('link', { name: 'Mis datos' })).toHaveAttribute('href', '/familia/mis-datos');
    expect(within(other).queryByRole('button', { name: /Editar/ })).not.toBeInTheDocument();
    expect(within(other).getByText('Esta persona tiene su propia cuenta y actualiza sus datos ella misma.')).toBeInTheDocument();
  });

  it('adds an adult with CSRF and an Idempotency-Key, trimmed, and shows them as pending the club review', async () => {
    await open();
    const user = userEvent.setup();
    await fillAdd(user, { nombre: '  Laura ', apellido: 'Gómez', dni: '30.111.222', parentesco: 'Tía' });
    await user.click(screen.getByRole('button', { name: 'Guardar adulto' }));

    expect(await screen.findByText(/Listo: cargamos al adulto/)).toBeInTheDocument();
    expect(writes()).toHaveLength(1);
    expect(writes()[0]!.csrfHeader).not.toBeNull();
    expect(writes()[0]!.idempotencyKey).toMatch(/^[0-9a-f-]{36}$/);
    expect(writes()[0]!.body).toMatchObject({ nombre: 'Laura', apellido: 'Gómez', dni: '30.111.222', parentesco: 'Tía' });
    const item = within(list()).getByRole('listitem');
    expect(within(item).getByText('Pendiente de revisión del club')).toBeInTheDocument();
    expect(document.body.textContent).not.toContain('30.111.222');
    expect(screen.queryByRole('form', { name: 'Agregar otro adulto' })).not.toBeInTheDocument();
  });

  it('says in the form that no account is needed and that no access to athletes is granted', async () => {
    await open();
    await userEvent.click(await screen.findByRole('button', { name: 'Agregar otro adulto' }));
    const form = screen.getByRole('form', { name: 'Agregar otro adulto' });
    expect(within(form).getByText(/No hace falta que tenga una cuenta/)).toBeInTheDocument();
    expect(within(form).getByText(/no le da acceso a los deportistas ni le asigna responsabilidades/)).toBeInTheDocument();
  });

  it('validates before sending, with fixed messages and nothing sent', async () => {
    await open();
    const user = userEvent.setup();
    await user.click(await screen.findByRole('button', { name: 'Agregar otro adulto' }));
    await user.type(screen.getByLabelText(/^DNI/), '12');
    await user.click(screen.getByRole('button', { name: 'Guardar adulto' }));
    expect(await screen.findByText('Ingresá el nombre.')).toBeInTheDocument();
    expect(screen.getByText('El DNI debe tener entre 7 y 9 dígitos.')).toBeInTheDocument();
    expect(writes()).toHaveLength(0);
  });

  it('refuses a duplicate of the own family with a fixed message that never echoes the data', async () => {
    backend.addAdult('familia-1', { nombre: 'Ana', apellido: 'Pérez', dni: '30111222' });
    await open();
    const user = userEvent.setup();
    await fillAdd(user, { nombre: 'Otra', apellido: 'Persona', dni: '30111222' });
    await user.click(screen.getByRole('button', { name: 'Guardar adulto' }));
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Ya hay un adulto cargado con esos datos en tu familia.');
    expect(alert.textContent).not.toMatch(/Otra|Persona|30111222/);
    // The same name without a DNI is a duplicate too (case and spaces ignored).
    await user.clear(screen.getByLabelText(/^DNI/));
    await user.clear(screen.getByLabelText(/^Nombre/));
    await user.clear(screen.getByLabelText(/^Apellido/));
    await user.type(screen.getByLabelText(/^Nombre/), ' ana ');
    await user.type(screen.getByLabelText(/^Apellido/), 'PÉREZ');
    await user.click(screen.getByRole('button', { name: 'Guardar adulto' }));
    await waitFor(() => expect(writes()).toHaveLength(2));
    expect(await screen.findByRole('alert')).toHaveTextContent('Ya hay un adulto cargado');
    expect(within(list()).getAllByRole('listitem')).toHaveLength(1);
  });

  it('reuses the Idempotency-Key when the same data are retried after a lost answer, and creates the adult once', async () => {
    await open();
    const user = userEvent.setup();
    await fillAdd(user, { nombre: 'Laura', apellido: 'Gómez' });
    backend.override('post', '/familia/tutores', 'lost');
    await user.click(screen.getByRole('button', { name: 'Guardar adulto' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('No pudimos confirmar si se guardó');
    // The list was refreshed: the adult is already there and the form is still open to retry.
    await waitFor(() => expect(within(list()).getAllByRole('listitem')).toHaveLength(1));
    await user.click(screen.getByRole('button', { name: 'Guardar adulto' }));
    await waitFor(() => expect(writes()).toHaveLength(2));
    expect(writes()[1]!.idempotencyKey).toBe(writes()[0]!.idempotencyKey);
    await waitFor(() => expect(screen.queryByRole('form', { name: 'Agregar otro adulto' })).not.toBeInTheDocument());
    expect(within(list()).getAllByRole('listitem')).toHaveLength(1);
  });

  it('uses a new Idempotency-Key for new data', async () => {
    await open();
    const user = userEvent.setup();
    await fillAdd(user, { nombre: 'Laura', apellido: 'Gómez' });
    backend.override('post', '/familia/tutores', 'network');
    await user.click(screen.getByRole('button', { name: 'Guardar adulto' }));
    await screen.findByRole('alert');
    await user.type(screen.getByLabelText(/^Parentesco/), 'Tía');
    await user.click(screen.getByRole('button', { name: 'Guardar adulto' }));
    await waitFor(() => expect(writes()).toHaveLength(2));
    expect(writes()[1]!.idempotencyKey).not.toBe(writes()[0]!.idempotencyKey);
  });

  it('edits an adult with the observed revision, the DNI empty meaning «keep it»', async () => {
    const adult = backend.addAdult('familia-1', { nombre: 'Ana', apellido: 'Pérez', dni: '30111222', telefono: '111' });
    await open();
    const user = userEvent.setup();
    await user.click(await screen.findByRole('button', { name: 'Editar los datos de Ana Pérez' }));
    expect(screen.getByLabelText(/^Cambiar el DNI/)).toHaveValue('');
    expect(screen.getByText(/Ahora figura ••••222/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Guardar cambios' })).toBeDisabled();
    await user.clear(screen.getByLabelText(/^Teléfono/));
    await user.type(screen.getByLabelText(/^Teléfono/), '222');
    await user.click(screen.getByRole('button', { name: 'Guardar cambios' }));

    expect(await screen.findByText('Listo: guardamos los cambios.')).toBeInTheDocument();
    expect(writes()).toHaveLength(1);
    expect(writes()[0]!.url).toBe(`/familia/tutores/${adult.id}`);
    expect(writes()[0]!.body).toMatchObject({ versionEsperada: '0', telefono: '222' });
    expect(writes()[0]!.body).not.toHaveProperty('dni');
    expect(backend.adultById(adult.id)).toMatchObject({ dni: '30111222', telefono: '222' });
  });

  it('refetches and explains when someone else changed the adult meanwhile', async () => {
    const adult = backend.addAdult('familia-1', { nombre: 'Ana', apellido: 'Pérez', telefono: '111' });
    await open();
    const user = userEvent.setup();
    await user.click(await screen.findByRole('button', { name: 'Editar los datos de Ana Pérez' }));
    backend.touchAdult(adult.id);
    await user.type(screen.getByLabelText(/^Teléfono/), '9');
    await user.click(screen.getByRole('button', { name: 'Guardar cambios' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Estos datos cambiaron mientras los editabas');
    await waitFor(() => expect(backend.requests.filter((r) => r.method === 'get' && r.url === '/familia/tutores').length).toBeGreaterThan(1));
  });

  it('locks name and DNI of a verified identity with an explanation, and the contact stays editable', async () => {
    backend.addAdult('familia-1', { nombre: 'Beto', apellido: 'Sosa', dni: '30111222', estado: 'VERIFICADO' });
    await open();
    const user = userEvent.setup();
    await user.click(await screen.findByRole('button', { name: 'Editar los datos de Beto Sosa' }));
    expect(screen.getByText('Estos datos ya los confirmó el club. Para cambiarlos, avisale al club.')).toBeInTheDocument();
    expect(screen.getByLabelText(/^Nombre/)).toBeDisabled();
    expect(screen.getByLabelText(/^Apellido/)).toBeDisabled();
    expect(screen.queryByLabelText(/DNI/)).not.toBeInTheDocument();
    await user.type(screen.getByLabelText(/^Teléfono/), '555');
    await user.click(screen.getByRole('button', { name: 'Guardar cambios' }));
    expect(await screen.findByText('Listo: guardamos los cambios.')).toBeInTheDocument();
    expect(within(list()).getByText('Verificado por el club')).toBeInTheDocument();
  });

  it('locks the identity too when the club approved a DNI image, even if the data are not verified', async () => {
    backend.addAdult('familia-1', { nombre: 'Eva', apellido: 'Luna', dni: '30111222', estado: 'DECLARADO', dniFrente: 'APROBADO' });
    await open();
    await userEvent.click(await screen.findByRole('button', { name: 'Editar los datos de Eva Luna' }));
    expect(screen.getByLabelText(/^Nombre/)).toBeDisabled();
    expect(within(list()).getByText('Pendiente de revisión del club')).toBeInTheDocument();
  });

  it.each([
    ['IDENTIDAD_VERIFICADA', 'Estos datos ya los confirmó el club'],
    ['IDENTIDAD_CON_DNI_APROBADO', 'Estos datos ya los confirmó el club'],
    ['TUTOR_CON_CUENTA', 'actualiza sus datos ella misma'],
    ['TUTOR_NO_ENCONTRADO', 'Esa persona ya no figura en tu familia'],
    ['TUTOR_DUPLICADO', 'Ya hay un adulto cargado'],
    ['TOPE_TUTORES_ALCANZADO', 'máximo de adultos'],
    ['IDEMPOTENCY_KEY_REUTILIZADA', 'cambió a mitad de camino'],
    ['AUTOSERVICIO_TUTORES_DESHABILITADO', 'todavía no está disponible'],
  ])('shows a fixed friendly message for %s without echoing the server text', async (code, expected) => {
    const adult = backend.addAdult('familia-1', { nombre: 'Ana', apellido: 'Pérez' });
    await open();
    const user = userEvent.setup();
    await user.click(await screen.findByRole('button', { name: 'Editar los datos de Ana Pérez' }));
    await user.type(screen.getByLabelText(/^Teléfono/), '1');
    backend.override('put', `/familia/tutores/${adult.id}`, { status: code === 'AUTOSERVICIO_TUTORES_DESHABILITADO' ? 503 : 409, body: { codigo: code, mensaje: 'TEXTO-DEL-SERVIDOR Pérez' } });
    await user.click(screen.getByRole('button', { name: 'Guardar cambios' }));
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent(expected);
    expect(alert.textContent).not.toContain('TEXTO-DEL-SERVIDOR');
  });

  it('shows the reason the club gave for an observation and the correction goes back to the club', async () => {
    backend.addAdult('familia-1', { nombre: 'Carla', apellido: 'Ruiz', estado: 'OBSERVADO', motivoObservacion: 'Revisá el apellido.' });
    await open();
    const user = userEvent.setup();
    expect(await screen.findByText(/Revisá el apellido\./)).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Editar los datos de Carla Ruiz' }));
    await user.type(screen.getByLabelText(/^Teléfono/), '123');
    await user.click(screen.getByRole('button', { name: 'Guardar cambios' }));
    expect(await screen.findByText('Listo: guardamos los cambios.')).toBeInTheDocument();
    expect(within(list()).getByText('Pendiente de revisión del club')).toBeInTheDocument();
    expect(screen.queryByText(/Revisá el apellido/)).not.toBeInTheDocument();
  });

  it('with the flag off keeps the list readable and offers no add or edit', async () => {
    backend.adultsSelfService.enabled = false;
    backend.addAdult('familia-1', { nombre: 'Ana', apellido: 'Pérez' });
    await open();
    expect(await screen.findByText(/todavía no está disponible/)).toBeInTheDocument();
    expect(within(list()).getByText('Ana Pérez')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Agregar|Editar/ })).not.toBeInTheDocument();
    expect(writes()).toHaveLength(0);
  });

  it('hides «Agregar» and asks to contact the club when the cap is reached; adults loaded by the club do not count', async () => {
    backend.adultsSelfService.cap = 2;
    backend.addAdult('familia-1', { nombre: 'Ana', apellido: 'Uno' });
    backend.addAdult('familia-1', { nombre: 'Beto', apellido: 'Dos', origen: 'ADMIN' });
    await open();
    expect(await screen.findByRole('button', { name: 'Agregar otro adulto' })).toBeInTheDocument(); // 1 of 2 loaded by the family
    cleanup();
    backend.addAdult('familia-1', { nombre: 'Carla', apellido: 'Tres' });
    await open();
    await screen.findByText(/avisale al club/);
    expect(screen.queryByRole('button', { name: 'Agregar otro adulto' })).not.toBeInTheDocument();
  });

  it('shows the cap error of the server when another device filled the last place', async () => {
    backend.adultsSelfService.cap = 1;
    await open();
    const user = userEvent.setup();
    await fillAdd(user, { nombre: 'Laura', apellido: 'Gómez' });
    backend.addAdult('familia-1', { nombre: 'Otro', apellido: 'Dispositivo' });
    await user.click(screen.getByRole('button', { name: 'Guardar adulto' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('máximo de adultos');
  });

  it('never lets an adult of another family be edited: its id is the same neutral «not found»', async () => {
    backend.addPortalFamily('familia-2', 'Otra');
    const foreign = backend.addAdult('familia-2', { nombre: 'Ajena', apellido: 'Otra' });
    await open();
    expect(screen.queryByText(/Ajena/)).not.toBeInTheDocument();
    // Even sending the foreign id directly (not reachable from the screen) gets the neutral «not found», nothing is changed,
    // and the screen would treat it as «that person is no longer in your family».
    const error = await updateAdult(foreign.id, { nombre: 'Cambiada', apellido: 'Otra', version: '0' }).then(() => null, (e: unknown) => e);
    expect(error).toBeInstanceOf(ApiError);
    expect((error as ApiError).status).toBe(404);
    expect((error as ApiError).code).toBe('TUTOR_NO_ENCONTRADO');
    expect(classify(error as ApiError)).toBe('gone');
    expect(foreign.nombre).toBe('Ajena');
  });

  it('is not shown to an administrator', async () => {
    cleanup();
    backend.signIn(admin.email);
    renderApp('/familia/adultos');
    await waitFor(() => expect(screen.queryByRole('heading', { level: 1, name: 'Otros adultos' })).not.toBeInTheDocument());
  });
});
