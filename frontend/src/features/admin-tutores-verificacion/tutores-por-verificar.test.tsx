import { cleanup, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import type { FakeBackend } from '@/test/fakeBackend';
import { createBackend, family, renderApp } from '@/test/render';

// MOCK backend (src/test/fakeBackend.ts mirrors VerificacionTutoresController). Real-backend evidence: e2e/adultos-familia.real.spec.ts.
let backend: FakeBackend;

beforeEach(() => {
  localStorage.clear();
  sessionStorage.clear();
  backend = createBackend();
  backend.signIn('marcos@example.com');
  backend.addPortalFamily('fam-a', 'Familia Aguirre');
  backend.addPortalFamily('fam-b', 'Familia Benítez');
});

afterEach(() => cleanup());

const decisions = () => backend.requests.filter((r) => r.method === 'post' && r.url.endsWith('/verificacion'));
const batches = () => backend.requests.filter((r) => r.method === 'post' && r.url === '/admin/tutores/verificacion-lote');
const inbox = () => screen.getByRole('list', { name: 'Tutores por verificar' });
const rowOf = (name: string) => within(inbox()).getAllByRole('listitem').find((li) => li.textContent?.includes(name))!;

async function open(path = '/admin/tutores/por-verificar') {
  renderApp(path);
  await screen.findByRole('heading', { level: 1, name: 'Tutores por verificar' });
  await waitFor(() => expect(screen.queryByText(/Cargando los tutores/)).not.toBeInTheDocument());
}

describe('Tutores por verificar (mock backend)', () => {
  it('has a menu entry next to «Documentos pendientes» and keeps the previous entries', async () => {
    renderApp('/admin/documentos/pendientes');
    const nav = await screen.findByRole('navigation', { name: 'Principal' });
    const labels = within(nav).getAllByRole('link').map((a) => a.textContent);
    expect(labels).toContain('Documentos pendientes');
    expect(labels.indexOf('Tutores por verificar')).toBe(labels.indexOf('Documentos pendientes') + 1);
    expect(labels).toEqual(expect.arrayContaining(['Familias', 'Deportistas', 'Temporadas', 'Invitaciones']));
  });

  it('shows the declared data ready to verify, oldest first, with the family link and the document states', async () => {
    const ana = backend.addAdult('fam-a', { nombre: 'Ana', apellido: 'Pérez', dni: '30111222', telefono: '1155', email: 'ana@example.test', parentesco: 'Madre', dniFrente: 'APROBADO' });
    backend.addAdult('fam-b', { nombre: 'Beto', apellido: 'Sosa' });
    backend.addAdult('fam-b', { nombre: 'Observado', apellido: 'Aparte', estado: 'OBSERVADO', motivoObservacion: 'x' });
    await open();
    const rows = within(inbox()).getAllByRole('listitem');
    expect(rows).toHaveLength(2);
    expect(rows[0]).toHaveTextContent('Ana Pérez');
    expect(rows[1]).toHaveTextContent('Beto Sosa');
    const first = rows[0]!;
    expect(first).toHaveTextContent('30111222');
    expect(first).toHaveTextContent('1155');
    expect(first).toHaveTextContent('ana@example.test');
    expect(first).toHaveTextContent('Madre');
    expect(within(first).getByText('DNI frente:').nextSibling).toHaveTextContent('Aprobado');
    expect(within(first).getByText('DNI dorso:').nextSibling).toHaveTextContent('Sin cargar');
    const link = within(first).getByRole('link', { name: 'Ver la ficha de Ana Pérez' });
    expect(link).toHaveTextContent('Familia Aguirre');
    expect(link).toHaveAttribute('href', `/admin/familias/fam-a/tutores/${ana.id}`);
    expect(screen.getByText(/no verifica la identidad/)).toBeInTheDocument();
  });

  it('warns, without naming anyone, when adults of other families share the DNI', async () => {
    backend.addAdult('fam-a', { nombre: 'Ana', apellido: 'Pérez', dni: '30111222' });
    backend.addAdult('fam-b', { nombre: 'Ana', apellido: 'Perez', dni: '30111222' });
    await open();
    expect(within(rowOf('Ana Pérez')).getByText('Hay 1 adulto de otra familia con el mismo DNI.')).toBeInTheDocument();
    cleanup();
    backend.addAdult('fam-b', { nombre: 'Tercera', apellido: 'Copia', dni: '30111222' });
    await open();
    expect(within(rowOf('Ana Pérez')).getByText('Hay 2 adultos de otra familia con el mismo DNI.')).toBeInTheDocument();
  });

  it('verifies one with the revision it saw, no retyping, then refreshes the list', async () => {
    const adult = backend.addAdult('fam-a', { nombre: 'Ana', apellido: 'Pérez', dni: '30111222' });
    await open();
    await userEvent.click(within(rowOf('Ana Pérez')).getByRole('button', { name: 'Verificar a Ana Pérez' }));
    expect(await screen.findByText('Listo: los datos quedaron verificados.')).toBeInTheDocument();
    expect(decisions()).toHaveLength(1);
    expect(decisions()[0]!.csrfHeader).not.toBeNull();
    expect(decisions()[0]!.url).toBe(`/admin/tutores/${adult.id}/verificacion`);
    expect(decisions()[0]!.body).toEqual({ decision: 'VERIFICAR', versionEsperada: '0' });
    expect(backend.adultById(adult.id)!.estado).toBe('VERIFICADO');
    await screen.findByText('No hay tutores pendientes de verificar.');
  });

  it('observes with a required reason (1 to 500 characters) and warns that the family will see it', async () => {
    const adult = backend.addAdult('fam-a', { nombre: 'Ana', apellido: 'Pérez' });
    await open();
    const user = userEvent.setup();
    await user.click(within(rowOf('Ana Pérez')).getByRole('button', { name: 'Observar a Ana Pérez' }));
    const form = screen.getByRole('form', { name: 'Observar los datos de Ana Pérez' });
    expect(within(form).getByText(/La familia va a ver este motivo/)).toBeInTheDocument();
    await user.click(within(form).getByRole('button', { name: 'Enviar observación' }));
    expect(await within(form).findByText('Escribí qué tiene que corregir la familia.')).toBeInTheDocument();
    expect(decisions()).toHaveLength(0);
    const box = within(form).getByLabelText('Qué tiene que corregir la familia');
    expect(box).toHaveAttribute('maxlength', '500');
    await user.type(box, '  Falta el segundo apellido. ');
    await user.click(within(form).getByRole('button', { name: 'Enviar observación' }));
    expect(await screen.findByText('Listo: la familia va a ver tu observación.')).toBeInTheDocument();
    expect(decisions()[0]!.body).toEqual({ decision: 'OBSERVAR', motivo: 'Falta el segundo apellido.', versionEsperada: '0' });
    expect(backend.adultById(adult.id)).toMatchObject({ estado: 'OBSERVADO', motivoObservacion: 'Falta el segundo apellido.' });
  });

  it('lists the observed ones with the reason on the OBSERVADO filter, and offers no checkbox there', async () => {
    backend.addAdult('fam-a', { nombre: 'Ana', apellido: 'Pérez', estado: 'OBSERVADO', motivoObservacion: 'Revisá el DNI.' });
    await open('/admin/tutores/por-verificar?estado=OBSERVADO');
    expect(within(rowOf('Ana Pérez')).getByText(/Revisá el DNI\./)).toBeInTheDocument();
    expect(screen.queryByRole('checkbox')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Verificar seleccionados/ })).not.toBeInTheDocument();
    expect(backend.requests.some((r) => r.url.includes('estado=OBSERVADO'))).toBe(true);
  });

  it.each([
    ['EDICION_DESACTUALIZADA', 409, 'cambiaron mientras los revisabas'],
    ['ESTADO_INCOMPATIBLE', 409, 'ya no está en un estado'],
    ['FAMILIA_INACTIVA', 409, 'familia de esa persona está inactiva'],
    ['TUTOR_NO_ENCONTRADO', 404, 'ya no existe'],
  ])('handles %s on a single decision by refetching with a fixed message', async (code, status, expected) => {
    const adult = backend.addAdult('fam-a', { nombre: 'Ana', apellido: 'Pérez' });
    await open();
    backend.override('post', `/admin/tutores/${adult.id}/verificacion`, { status, body: { codigo: code, mensaje: 'TEXTO-DEL-SERVIDOR' } });
    const before = backend.requests.filter((r) => r.method === 'get' && r.url.startsWith('/admin/tutores/por-verificar')).length;
    await userEvent.click(within(rowOf('Ana Pérez')).getByRole('button', { name: 'Verificar a Ana Pérez' }));
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent(expected);
    expect(alert.textContent).not.toContain('TEXTO-DEL-SERVIDOR');
    await waitFor(() => expect(backend.requests.filter((r) => r.method === 'get' && r.url.startsWith('/admin/tutores/por-verificar')).length).toBeGreaterThan(before));
  });

  it('shows a stale revision when the family changed the data after ADMIN loaded the list', async () => {
    const adult = backend.addAdult('fam-a', { nombre: 'Ana', apellido: 'Pérez' });
    await open();
    backend.touchAdult(adult.id);
    await userEvent.click(within(rowOf('Ana Pérez')).getByRole('button', { name: 'Verificar a Ana Pérez' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('cambiaron mientras los revisabas');
    expect(backend.adultById(adult.id)!.estado).toBe('DECLARADO');
  });

  it('verifies the selected ones in batch and shows each individual result, mixed outcomes included', async () => {
    const ok = backend.addAdult('fam-a', { nombre: 'Ana', apellido: 'Uno' });
    const stale = backend.addAdult('fam-a', { nombre: 'Beto', apellido: 'Dos' });
    const busy = backend.addAdult('fam-b', { nombre: 'Carla', apellido: 'Tres' });
    const already = backend.addAdult('fam-b', { nombre: 'Dora', apellido: 'Cuatro' });
    const untouched = backend.addAdult('fam-b', { nombre: 'Eva', apellido: 'Cinco' });
    await open();
    const user = userEvent.setup();
    for (const name of ['Ana Uno', 'Beto Dos', 'Carla Tres', 'Dora Cuatro']) await user.click(within(inbox()).getByRole('checkbox', { name: `Seleccionar a ${name}` }));
    expect(screen.getByRole('button', { name: 'Verificar seleccionados (4)' })).toBeInTheDocument();
    backend.touchAdult(stale.id);
    backend.markAdultBusy(busy.id);
    Object.assign(backend.adultById(already.id)!, { estado: 'VERIFICADO' });
    await user.click(screen.getByRole('button', { name: 'Verificar seleccionados (4)' }));

    const results = await screen.findByRole('region', { name: 'Resultado de la verificación en lote' });
    expect(batches()).toHaveLength(1);
    expect(batches()[0]!.csrfHeader).not.toBeNull();
    expect(batches()[0]!.body).toEqual({ tutores: [ok, stale, busy, already].map((a) => ({ tutorId: a.id, versionEsperada: '0' })) });
    const lines = within(results).getAllByRole('listitem');
    expect(lines).toHaveLength(4);
    expect(lines[0]).toHaveTextContent('Ana Uno');
    expect(lines[0]).toHaveTextContent('Verificado');
    expect(lines[1]).toHaveTextContent('Beto Dos');
    expect(lines[1]).toHaveTextContent('los datos cambiaron mientras los revisabas');
    expect(lines[2]).toHaveTextContent('estaba ocupado');
    expect(lines[3]).toHaveTextContent('Ya estaba verificado');
    expect(within(results).getByText('2 de 4 quedaron verificadas.')).toBeInTheDocument();
    expect(backend.adultById(ok.id)!.estado).toBe('VERIFICADO');
    expect(backend.adultById(stale.id)!.estado).toBe('DECLARADO');
    expect(backend.adultById(untouched.id)!.estado).toBe('DECLARADO');
    // Refetched: only the ones still pending remain, and the selection is cleared.
    await waitFor(() => expect(within(inbox()).getAllByRole('listitem')).toHaveLength(3));
    expect(screen.getByRole('button', { name: 'Verificar seleccionados' })).toBeDisabled();
  });

  it('explains the rest of the individual batch outcomes in plain Spanish', async () => {
    const gone = backend.addAdult('fam-a', { nombre: 'Ana', apellido: 'Uno' });
    const inactiveFamily = backend.addAdult('fam-a', { nombre: 'Beto', apellido: 'Dos' });
    const observed = backend.addAdult('fam-b', { nombre: 'Carla', apellido: 'Tres' });
    await open();
    const user = userEvent.setup();
    await user.click(screen.getByRole('button', { name: 'Seleccionar los de esta página' }));
    Object.assign(backend.adultById(gone.id)!, { activo: false });
    Object.assign(backend.adultById(inactiveFamily.id)!, { familyId: 'fam-inactiva' });
    backend.addPortalFamily('fam-inactiva', 'Inactiva');
    backend.setPortalFamilyActive('fam-inactiva', false);
    Object.assign(backend.adultById(observed.id)!, { estado: 'OBSERVADO', motivoObservacion: 'x' });
    await user.click(screen.getByRole('button', { name: 'Verificar seleccionados (3)' }));
    const results = await screen.findByRole('region', { name: 'Resultado de la verificación en lote' });
    const text = results.textContent ?? '';
    expect(text).toContain('su estado actual no lo permite');
    expect(text).toContain('la familia está inactiva');
    expect(text).not.toMatch(/ESTADO_INCOMPATIBLE|FAMILIA_INACTIVA/);
  });

  it('shows a batch that fails as a whole with a fixed message', async () => {
    backend.addAdult('fam-a', { nombre: 'Ana', apellido: 'Uno' });
    await open();
    backend.override('post', '/admin/tutores/verificacion-lote', 'network');
    await userEvent.click(within(inbox()).getByRole('checkbox', { name: 'Seleccionar a Ana Uno' }));
    await userEvent.click(screen.getByRole('button', { name: 'Verificar seleccionados (1)' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('No pudimos conectar con el servidor');
  });

  it('paginates the inbox', async () => {
    for (let i = 0; i < 25; i += 1) backend.addAdult('fam-a', { nombre: `Persona${String(i).padStart(2, '0')}`, apellido: 'Lote' });
    await open();
    expect(within(inbox()).getAllByRole('listitem')).toHaveLength(20);
    await userEvent.click(screen.getByRole('button', { name: 'Siguiente' }));
    await waitFor(() => expect(within(inbox()).getAllByRole('listitem')).toHaveLength(5));
    expect(screen.getByTestId('location')).toHaveTextContent('pagina=2');
    expect(backend.requests.some((r) => r.url.includes('page=1') && r.url.includes('size=20'))).toBe(true);
  });

  it('shows an empty state and keeps the screen away from families', async () => {
    await open();
    expect(screen.getByText('No hay tutores pendientes de verificar.')).toBeInTheDocument();
    cleanup();
    backend.signIn(family.email);
    renderApp('/admin/tutores/por-verificar');
    await waitFor(() => expect(screen.queryByRole('heading', { level: 1, name: 'Tutores por verificar' })).not.toBeInTheDocument());
  });

  it('shows the verification state and the observation reason on the admin tutor page, and nothing for a tutor without the new fields', async () => {
    const observed = backend.addFamily('Gómez', {
      tutores: [{ id: 't-obs', nombre: 'Rosa', apellido: 'Sintética', dni: '30111222', dniEnmascarado: '••••••222', telefono: null, email: null, parentesco: null, activo: true, version: 1, verificacion: 'OBSERVADO', motivoObservacion: 'Revisá el apellido.' }],
    });
    renderApp(`/admin/familias/${observed.id}/tutores/t-obs`);
    expect(await screen.findByText('Datos observados')).toBeInTheDocument();
    expect(screen.getByText(/Motivo que ve la familia: Revisá el apellido\./)).toBeInTheDocument();
    cleanup();
    const plain = backend.addFamily('Pérez', {
      tutores: [{ id: 't-plain', nombre: 'Laura', apellido: 'Plana', dni: null, dniEnmascarado: null, telefono: null, email: null, parentesco: null, activo: true, version: 1 }],
    });
    renderApp(`/admin/familias/${plain.id}/tutores/t-plain`);
    expect(await screen.findByRole('heading', { level: 1, name: 'Laura Plana' })).toBeInTheDocument();
    expect(screen.queryByText(/Datos observados|Identidad verificada|Datos declarados/)).not.toBeInTheDocument();
  });
});
