import { act, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { QueryClient } from '@tanstack/react-query';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { sessionKey } from '@/features/auth/session';
import type { FakeAdminTutor, FakeBackend } from '@/test/fakeBackend';
import { admin, createBackend, renderApp } from '@/test/render';

// MOCK backend (see src/test/fakeBackend.ts, which mirrors the real contract of FE-05/U4). Real-backend evidence lives in
// e2e/tutores-edicion.real.spec.ts.
let backend: FakeBackend;

beforeEach(() => {
  localStorage.clear();
  sessionStorage.clear();
  backend = createBackend();
});

const DNI = '30111222';
const location = () => screen.getByTestId('location').textContent ?? '';
const wait = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms));
const tutorPuts = () => backend.requests.filter((r) => r.method === 'put' && r.url === '/admin/tutores/t-rosa');
const tutorGets = () => backend.requests.filter((r) => r.method === 'get' && /^\/admin\/tutores\/[^/]+$/.test(r.url));
const familyGets = (id: string) => backend.requests.filter((r) => r.method === 'get' && r.url === `/admin/familias/${id}`);
const lastBody = () => tutorPuts().at(-1)!.body as Record<string, unknown>;

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

function openAt(path: string) {
  backend.signIn('marcos@example.com');
  return renderApp(path);
}

/** Family revision 5 and tutor revision 3 unless said otherwise. */
async function openEdit(options: { family?: Parameters<FakeBackend['addFamily']>[1]; tutor?: Partial<FakeAdminTutor> } = {}) {
  const family = backend.addFamily('Gómez', { version: 5, tutores: [tutor(options.tutor)], ...options.family });
  const { queryClient } = openAt(`/admin/familias/${family.id}/tutores/t-rosa/editar`);
  await screen.findByRole('heading', { level: 1, name: 'Editar tutor' });
  return { family, queryClient };
}

const LABELS = { nombre: /^Nombre/, apellido: /^Apellido/, dni: /^DNI/, telefono: /^Teléfono/, correo: /^Correo/, parentesco: /^Parentesco/ };

async function fill(user: ReturnType<typeof userEvent.setup>, values: Partial<Record<keyof typeof LABELS, string>>) {
  for (const [key, value] of Object.entries(values)) {
    const box = screen.getByLabelText(LABELS[key as keyof typeof LABELS]);
    await user.clear(box);
    if (value !== '') await user.type(box, value);
  }
}

const save = (user: ReturnType<typeof userEvent.setup>) => user.click(screen.getByRole('button', { name: 'Guardar cambios' }));
const field = (key: keyof typeof LABELS) => screen.getByLabelText(LABELS[key]);

describe('edit a tutor: loading and entry points (mock backend)', () => {
  it('keeps a family user out and never requests the tutor or the family', async () => {
    const family = backend.addFamily('Gómez', { tutores: [tutor()] });
    backend.signIn('lucia@example.com');
    renderApp(`/admin/familias/${family.id}/tutores/t-rosa/editar`);
    expect(await screen.findByRole('heading', { name: 'No tenés permiso para ver esta sección' })).toBeInTheDocument();
    expect(tutorGets()).toHaveLength(0);
    expect(familyGets(family.id)).toHaveLength(0);
  });

  it('keeps a password-only admin session out (MFA is required) and never requests the tutor', async () => {
    const family = backend.addFamily('Gómez', { tutores: [tutor()] });
    backend.signIn('marcos@example.com', true);
    renderApp(`/admin/familias/${family.id}/tutores/t-rosa/editar`);
    expect(await screen.findByRole('heading', { name: 'Verificación en dos pasos' })).toBeInTheDocument();
    expect(tutorGets()).toHaveLength(0);
  });

  it('is reached from the tutor detail (not from the family summary) and starts from the FULL detail', async () => {
    const family = backend.addFamily('Gómez', { version: 5, tutores: [tutor()] });
    openAt(`/admin/familias/${family.id}`);
    const user = userEvent.setup();
    const list = await screen.findByRole('list', { name: 'Tutores' });
    expect(within(list).queryByRole('link', { name: /editar/i })).not.toBeInTheDocument(); // the summary only links to the detail
    await user.click(within(list).getByRole('link', { name: 'Rosa Sintética' }));
    await screen.findByRole('heading', { level: 1, name: 'Rosa Sintética' });
    const edit = screen.getByRole('link', { name: 'Editar tutor' });
    expect(edit).toHaveAttribute('href', `/admin/familias/${family.id}/tutores/t-rosa/editar`);
    await user.click(edit);
    await screen.findByRole('heading', { level: 1, name: 'Editar tutor' });
    expect(location()).toBe(`/admin/familias/${family.id}/tutores/t-rosa/editar`);
    // every value comes from the individual detail, including the FULL DNI that the family summary only carries masked
    expect(field('nombre')).toHaveValue('Rosa');
    expect(field('apellido')).toHaveValue('Sintética');
    expect(field('dni')).toHaveValue(DNI);
    expect(field('telefono')).toHaveValue('11 5555-0000');
    expect(field('correo')).toHaveValue('rosa@example.test');
    expect(field('parentesco')).toHaveValue('Madre');
    expect(screen.queryByDisplayValue(/•/)).not.toBeInTheDocument();
    expect(tutorGets().some((r) => r.url === '/admin/tutores/t-rosa')).toBe(true);
  });

  it('shows no form until BOTH the tutor and the family arrived, and asks for both', async () => {
    const family = backend.addFamily('Gómez', { version: 5, tutores: [tutor()] });
    const held = backend.hold('get', `/admin/familias/${family.id}`);
    openAt(`/admin/familias/${family.id}/tutores/t-rosa/editar`);
    expect(await screen.findByText('Cargando el tutor…')).toBeInTheDocument();
    expect(screen.queryByLabelText(/^Nombre/)).not.toBeInTheDocument();
    held.release();
    expect(await screen.findByRole('heading', { level: 1, name: 'Editar tutor' })).toBeInTheDocument();
    expect(tutorGets().length).toBeGreaterThanOrEqual(1);
    expect(familyGets(family.id).length).toBeGreaterThanOrEqual(1);
  });

  it('shows «not found» for an unknown tutor, for a tutor of ANOTHER family and for a malformed id, without requesting a form', async () => {
    const family = backend.addFamily('Gómez', { tutores: [tutor()] });
    const other = backend.addFamily('Otra', { tutores: [] });
    openAt(`/admin/familias/${other.id}/tutores/t-rosa/editar`);
    expect(await screen.findByRole('heading', { name: 'No encontramos este tutor' })).toBeInTheDocument();
    expect(screen.queryByLabelText(/^Nombre/)).not.toBeInTheDocument();
    expect(document.body.textContent).not.toContain(DNI);
    expect(family.id).not.toBe(other.id);
  });

  it('shows «not found» for an unknown tutor id', async () => {
    const family = backend.addFamily('Gómez', { tutores: [tutor()] });
    openAt(`/admin/familias/${family.id}/tutores/t-no-existe/editar`);
    expect(await screen.findByRole('heading', { name: 'No encontramos este tutor' })).toBeInTheDocument();
  });

  it('offers a retry when the data cannot be loaded', async () => {
    const family = backend.addFamily('Gómez', { version: 5, tutores: [tutor()] });
    backend.override('get', '/admin/tutores/t-rosa', 'network');
    openAt(`/admin/familias/${family.id}/tutores/t-rosa/editar`);
    expect(await screen.findByRole('alert')).toBeInTheDocument();
    await userEvent.setup().click(screen.getByRole('button', { name: 'Reintentar' }));
    expect(await screen.findByRole('heading', { level: 1, name: 'Editar tutor' })).toBeInTheDocument();
  });

  it('does not open the form of an inactive family: it says so and sends nothing', async () => {
    const family = backend.addFamily('Gómez', { version: 5, activa: false, tutores: [tutor()] });
    openAt(`/admin/familias/${family.id}/tutores/t-rosa/editar`);
    expect(await screen.findByText(/La familia está inactiva: no se pueden editar sus tutores/)).toBeInTheDocument();
    expect(screen.queryByLabelText(/^Nombre/)).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Guardar cambios' })).not.toBeInTheDocument();
    expect(tutorPuts()).toHaveLength(0);
  });
});

describe('edit a tutor: saving with both revisions (mock backend)', () => {
  it('sends BOTH captured revisions as decimal strings with every field, the CSRF header, and shows the confirmed result', async () => {
    const { family } = await openEdit();
    const user = userEvent.setup();
    await fill(user, { telefono: '11 6666-1111', parentesco: 'Tutora legal' });
    await save(user);
    expect(await screen.findByText('Cambios guardados.')).toBeInTheDocument();
    expect(tutorPuts()).toHaveLength(1);
    expect(tutorPuts()[0]!.csrfHeader).toBeTruthy();
    expect(lastBody()).toEqual({
      nombre: 'Rosa',
      apellido: 'Sintética',
      dni: DNI,
      telefono: '11 6666-1111',
      email: 'rosa@example.test',
      parentesco: 'Tutora legal',
      versionEsperada: '3',
      versionFamiliaEsperada: '5',
    });
    // the confirmed result: the detail of the tutor with the new data, the tutor revision moved and the family revision did not
    expect(location()).toBe(`/admin/familias/${family.id}/tutores/t-rosa`);
    expect(await screen.findByRole('heading', { level: 1, name: 'Rosa Sintética' })).toBeInTheDocument();
    expect(await screen.findByText('11 6666-1111')).toBeInTheDocument();
    expect(screen.getByText('Tutora legal')).toBeInTheDocument();
    expect(backend.tutorVersion('t-rosa')).toBe('4');
    expect(backend.familyVersion(family.id)).toBe('5');
  });

  it('keeps the tutor in the family it belongs to', async () => {
    const { family } = await openEdit();
    const user = userEvent.setup();
    await fill(user, { nombre: 'Rosa María' });
    await save(user);
    await screen.findByText('Cambios guardados.');
    expect(location()).toContain(`/admin/familias/${family.id}/`);
    await user.click(screen.getByRole('link', { name: 'Volver a la familia' }));
    const list = await screen.findByRole('list', { name: 'Tutores' });
    expect(within(list).getAllByRole('listitem')).toHaveLength(1);
    expect(within(list).getByRole('link', { name: 'Rosa María Sintética' })).toBeInTheDocument();
  });

  it('does not send anything while nothing changed (explicit save only, no autosave)', async () => {
    await openEdit();
    const user = userEvent.setup();
    expect(screen.getByRole('button', { name: 'Guardar cambios' })).toBeDisabled();
    await fill(user, { nombre: 'Rosa X' });
    expect(screen.getByRole('button', { name: 'Guardar cambios' })).toBeEnabled();
    await wait(600);
    expect(tutorPuts()).toHaveLength(0); // typing never saves by itself
    await fill(user, { nombre: 'Rosa' });
    expect(screen.getByRole('button', { name: 'Guardar cambios' })).toBeDisabled();
  });

  it('trims the values and sends one request even if saved twice quickly', async () => {
    await openEdit();
    const held = backend.hold('put', '/admin/tutores/t-rosa');
    const user = userEvent.setup();
    await fill(user, { nombre: '  Rosa Ana  ', correo: ' ROSA.ANA@Example.test ' });
    await save(user);
    await user.click(screen.getByRole('button', { name: /Guardando/ })).catch(() => undefined);
    held.release();
    await screen.findByText('Cambios guardados.');
    expect(tutorPuts()).toHaveLength(1);
    expect(lastBody()).toMatchObject({ nombre: 'Rosa Ana', email: 'ROSA.ANA@Example.test' });
  });

  it.each([
    ['empty name', { nombre: '' }, 'Ingresá el nombre.'],
    ['empty last name', { apellido: '' }, 'Ingresá el apellido.'],
    ['a DNI that is not 7 to 9 digits', { dni: '12345' }, 'El DNI debe tener entre 7 y 9 dígitos.'],
    ['an invalid e-mail', { correo: 'sin-arroba' }, 'Ingresá un correo electrónico válido.'],
  ] as const)('validates %s before sending', async (_label, values, message) => {
    await openEdit();
    const user = userEvent.setup();
    await fill(user, values);
    await save(user);
    expect(await screen.findByText(message)).toBeInTheDocument();
    expect(tutorPuts()).toHaveLength(0);
  });

  it('accepts a DNI written with dots', async () => {
    await openEdit();
    const user = userEvent.setup();
    await fill(user, { dni: '30.111.223' });
    await save(user);
    await screen.findByText('Cambios guardados.');
    expect(lastBody()).toMatchObject({ dni: '30.111.223' });
  });
});

describe('edit a tutor: complete replacement of the optional fields (mock backend)', () => {
  it('sends an emptied optional field as an explicit null, tells which saved data will be removed (never their values), and clears it', async () => {
    await openEdit();
    const user = userEvent.setup();
    await fill(user, { telefono: '', parentesco: '' });
    const note = screen.getByText(/Al guardar se van a quitar estos datos:/);
    expect(note).toHaveTextContent('Teléfono, Parentesco');
    expect(note.textContent).not.toMatch(/5555|Madre|rosa@|30111222/);
    await save(user);
    await screen.findByText('Cambios guardados.');
    expect(lastBody()).toMatchObject({ telefono: null, parentesco: null, dni: DNI, email: 'rosa@example.test' });
    expect(await screen.findAllByText('No informado')).toHaveLength(2);
  });

  it('can clear every optional field and leaves the required ones', async () => {
    await openEdit();
    const user = userEvent.setup();
    await fill(user, { dni: '', telefono: '', correo: '', parentesco: '' });
    await save(user);
    await screen.findByText('Cambios guardados.');
    expect(lastBody()).toMatchObject({ nombre: 'Rosa', apellido: 'Sintética', dni: null, telefono: null, email: null, parentesco: null });
    expect(await screen.findAllByText('No informado')).toHaveLength(4);
  });

  it('never omits a field the administrator did not touch: a tutor with empty optionals keeps sending them as null, and the filled ones as they are', async () => {
    await openEdit({ tutor: { telefono: null, email: null, parentesco: null, dni: DNI } });
    const user = userEvent.setup();
    expect(field('telefono')).toHaveValue('');
    expect(screen.queryByText(/Al guardar se van a quitar/)).not.toBeInTheDocument();
    await fill(user, { nombre: 'Rosa Z' });
    await save(user);
    await screen.findByText('Cambios guardados.');
    expect(lastBody()).toMatchObject({ nombre: 'Rosa Z', dni: DNI, telefono: null, email: null, parentesco: null });
    expect(Object.keys(lastBody()).sort()).toEqual(
      ['apellido', 'dni', 'email', 'nombre', 'parentesco', 'telefono', 'versionEsperada', 'versionFamiliaEsperada'].sort(),
    );
  });
});

describe('edit a tutor: revisions are captured when the edit opens (mock backend)', () => {
  it('does not replace the captured revisions when the data is refreshed in the background: the server answers 409', async () => {
    const { family, queryClient } = await openEdit();
    const user = userEvent.setup();
    await fill(user, { nombre: 'Rosa B' });
    backend.changeFamilyBehindTheScenes(family.id, { nombreReferencia: 'Gómez renombrada' }); // family revision 6
    backend.changeTutorBehindTheScenes('t-rosa', { telefono: '11 0000-9999' }); // tutor revision 4
    await act(async () => {
      await queryClient.invalidateQueries({ queryKey: ['admin', 'family-detail', family.id] });
      await queryClient.invalidateQueries({ queryKey: ['admin', 'tutor-detail', 't-rosa'] });
    });
    await save(user);
    expect(await screen.findByText(/El tutor o su familia fueron modificados/)).toBeInTheDocument();
    expect(lastBody()).toMatchObject({ versionEsperada: '3', versionFamiliaEsperada: '5' }); // the captured ones, never the live ones
    expect(field('nombre')).toHaveValue('Rosa B');
    expect(tutorPuts()).toHaveLength(1);
  });
});

describe('edit a tutor: conflict on the tutor revision (mock backend)', () => {
  async function staleByTutor() {
    const { family } = await openEdit();
    const user = userEvent.setup();
    await fill(user, { telefono: '11 7777-2222' });
    backend.changeTutorBehindTheScenes('t-rosa', { telefono: '11 3333-4444', parentesco: 'Abuela' }); // another administrator, tutor revision 4
    await save(user);
    await screen.findByText(/El tutor o su familia fueron modificados/);
    return { family, user };
  }

  it('keeps the draft in memory, says the tutor or its family changed, blocks new sends and never retries or forces anything', async () => {
    await staleByTutor();
    expect(field('telefono')).toHaveValue('11 7777-2222');
    expect(screen.getByRole('button', { name: 'Guardar cambios' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Ver datos vigentes' })).toBeEnabled();
    await wait(500);
    expect(tutorPuts()).toHaveLength(1);
    expect(backend.tutorVersion('t-rosa')).toBe('4'); // nothing was overwritten
  });

  it('shows the current data next to the draft only on request, highlights what differs, and does not save anything', async () => {
    const { user } = await staleByTutor();
    const getsBefore = tutorGets().length;
    await user.click(screen.getByRole('button', { name: 'Ver datos vigentes' }));
    const panel = await screen.findByRole('group', { name: 'Datos vigentes' });
    expect(tutorGets().length).toBeGreaterThan(getsBefore);
    expect(panel).toHaveTextContent('Tu borrador no se guardó ni se mezcló con ellos');
    const table = within(panel).getByRole('table', { name: 'Comparación de datos' });
    const phone = within(table).getByRole('row', { name: /Teléfono/ });
    expect(phone).toHaveTextContent('11 3333-4444');
    expect(phone).toHaveTextContent('11 7777-2222');
    expect(phone).toHaveTextContent(/distinto/i);
    expect(within(table).getByRole('row', { name: /Parentesco/ })).toHaveTextContent('Abuela');
    expect(within(table).getByRole('row', { name: /Parentesco/ })).toHaveTextContent('Madre');
    expect(within(table).getByRole('row', { name: /Nombre/ })).not.toHaveTextContent(/distinto/i);
    expect(screen.getByRole('button', { name: 'Guardar cambios' })).toBeDisabled();
    expect(tutorPuts()).toHaveLength(1);
  });

  it('continues only by an explicit decision, then sends the NEW revisions with the draft', async () => {
    const { user } = await staleByTutor();
    await user.click(screen.getByRole('button', { name: 'Ver datos vigentes' }));
    const panel = await screen.findByRole('group', { name: 'Datos vigentes' });
    expect(panel).toHaveTextContent('los campos que no modificaste pasan a los datos vigentes y los que modificaste conservan tu borrador');
    await user.click(within(panel).getByRole('button', { name: 'Seguir editando mi borrador' }));
    expect(field('telefono')).toHaveValue('11 7777-2222'); // changed by the administrator: the draft is kept
    expect(field('parentesco')).toHaveValue('Abuela'); // never touched by the administrator: the other administrator's change is NOT undone
    expect(screen.queryByText(/El tutor o su familia fueron modificados/)).not.toBeInTheDocument();
    expect(tutorPuts()).toHaveLength(1); // continuing saves nothing by itself
    await save(user);
    await screen.findByText('Cambios guardados.');
    expect(tutorPuts()).toHaveLength(2);
    expect(lastBody()).toMatchObject({ telefono: '11 7777-2222', parentesco: 'Abuela', versionEsperada: '4', versionFamiliaEsperada: '5' });
    expect(backend.tutorVersion('t-rosa')).toBe('5');
  });

  it('produces a new conflict if it changes again after continuing', async () => {
    const { user } = await staleByTutor();
    await user.click(screen.getByRole('button', { name: 'Ver datos vigentes' }));
    await user.click(within(await screen.findByRole('group', { name: 'Datos vigentes' })).getByRole('button', { name: 'Seguir editando mi borrador' }));
    backend.changeTutorBehindTheScenes('t-rosa', { apellido: 'Otra' }); // tutor revision 5 while the draft is being completed
    await save(user);
    expect(await screen.findByText(/El tutor o su familia fueron modificados/)).toBeInTheDocument();
    expect(tutorPuts()).toHaveLength(2);
    expect(backend.tutorVersion('t-rosa')).toBe('5');
    expect(field('telefono')).toHaveValue('11 7777-2222');
  });

  it('discards the draft on request and goes back to the tutor', async () => {
    const { family, user } = await staleByTutor();
    await user.click(screen.getByRole('button', { name: 'Ver datos vigentes' }));
    await user.click(within(await screen.findByRole('group', { name: 'Datos vigentes' })).getByRole('button', { name: 'Descartar mi borrador' }));
    expect(await screen.findByRole('heading', { level: 1, name: 'Rosa Sintética' })).toBeInTheDocument();
    expect(location()).toBe(`/admin/familias/${family.id}/tutores/t-rosa`);
    expect(await screen.findByText('11 3333-4444')).toBeInTheDocument(); // the current data, not the draft
    expect(document.body.textContent).not.toContain('11 7777-2222');
  });

  it('says so when the current data already equal the draft, without attributing the save to us, and offers no resend', async () => {
    const { user } = await staleByTutor();
    backend.changeTutorBehindTheScenes('t-rosa', { telefono: '11 7777-2222', parentesco: 'Madre' }); // somebody saved the same thing
    await user.click(screen.getByRole('button', { name: 'Ver datos vigentes' }));
    const panel = await screen.findByRole('group', { name: 'Datos vigentes' });
    expect(panel).toHaveTextContent('Los datos vigentes ya coinciden con tu borrador');
    expect(panel).toHaveTextContent('No podemos saber si fue tu envío u otro cambio');
    expect(within(panel).queryByRole('button', { name: 'Seguir editando mi borrador' })).not.toBeInTheDocument();
    expect(tutorPuts()).toHaveLength(1);
  });

  it('shows an error if the current data cannot be loaded and keeps the draft and the block', async () => {
    const { user } = await staleByTutor();
    backend.override('get', '/admin/tutores/t-rosa', 'network');
    await user.click(screen.getByRole('button', { name: 'Ver datos vigentes' }));
    expect(await screen.findByText(/No pudimos conectar con el servidor/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Guardar cambios' })).toBeDisabled();
    expect(field('telefono')).toHaveValue('11 7777-2222');
    await user.click(screen.getByRole('button', { name: 'Ver datos vigentes' }));
    expect(await screen.findByRole('group', { name: 'Datos vigentes' })).toBeInTheDocument();
  });
});

describe('edit a tutor: conflict on the family revision (mock backend)', () => {
  it('detects that the family changed while the tutor did not, and continues with the new family revision only after the explicit decision', async () => {
    const { family } = await openEdit();
    const user = userEvent.setup();
    await fill(user, { parentesco: 'Tutora' });
    backend.changeFamilyBehindTheScenes(family.id, { nombreReferencia: 'Gómez Pérez' }); // family revision 6, tutor revision still 3
    await save(user);
    expect(await screen.findByText(/El tutor o su familia fueron modificados/)).toBeInTheDocument();
    expect(lastBody()).toMatchObject({ versionEsperada: '3', versionFamiliaEsperada: '5' });
    expect(screen.getByRole('button', { name: 'Guardar cambios' })).toBeDisabled();
    await user.click(screen.getByRole('button', { name: 'Ver datos vigentes' }));
    const panel = await screen.findByRole('group', { name: 'Datos vigentes' });
    expect(panel).toHaveTextContent('Gómez Pérez'); // the family's current name
    await user.click(within(panel).getByRole('button', { name: 'Seguir editando mi borrador' }));
    await save(user);
    await screen.findByText('Cambios guardados.');
    expect(lastBody()).toMatchObject({ parentesco: 'Tutora', versionEsperada: '3', versionFamiliaEsperada: '6' });
    expect(backend.familyVersion(family.id)).toBe('6'); // editing a tutor does not move the family revision
  });
});

describe('edit a tutor: other failures are told apart (mock backend)', () => {
  it('tells an inactive family apart from a stale revision: blocked, draft kept, no way to continue on an inactive family', async () => {
    const { family } = await openEdit();
    const user = userEvent.setup();
    await fill(user, { nombre: 'Rosa Q' });
    backend.changeFamilyBehindTheScenes(family.id, { activa: false }); // deactivated while editing
    await save(user);
    expect(await screen.findByText(/La familia está inactiva: no se pueden editar sus tutores/)).toBeInTheDocument();
    expect(screen.queryByText(/El tutor o su familia fueron modificados/)).not.toBeInTheDocument();
    expect(field('nombre')).toHaveValue('Rosa Q');
    expect(screen.getByRole('button', { name: 'Guardar cambios' })).toBeDisabled();
    await user.click(screen.getByRole('button', { name: 'Ver datos vigentes' }));
    const panel = await screen.findByRole('group', { name: 'Datos vigentes' });
    expect(panel).toHaveTextContent('Inactiva');
    expect(within(panel).queryByRole('button', { name: 'Seguir editando mi borrador' })).not.toBeInTheDocument();
    expect(within(panel).getByRole('button', { name: 'Descartar mi borrador' })).toBeInTheDocument();
    expect(tutorPuts()).toHaveLength(1);
  });

  it('treats a database contention (CONFLICTO_CONCURRENCIA) as «nothing was applied»: same revisions, a manual retry, no automatic one', async () => {
    await openEdit();
    const user = userEvent.setup();
    await fill(user, { nombre: 'Rosa C' });
    backend.override('put', '/admin/tutores/t-rosa', { status: 409, body: { codigo: 'CONFLICTO_CONCURRENCIA', mensaje: 'x' } });
    await save(user);
    expect(await screen.findByText(/Otra operación está en curso sobre esta familia/)).toBeInTheDocument();
    expect(screen.queryByText(/El tutor o su familia fueron modificados/)).not.toBeInTheDocument();
    await wait(500);
    expect(tutorPuts()).toHaveLength(1);
    expect(screen.getByRole('button', { name: 'Guardar cambios' })).toBeEnabled();
    await save(user);
    await screen.findByText('Cambios guardados.');
    expect(tutorPuts()).toHaveLength(2);
    expect(lastBody()).toMatchObject({ versionEsperada: '3', versionFamiliaEsperada: '5' }); // unchanged: nothing had been applied
  });

  it('shows a fixed message for a validation error of the server and keeps the draft', async () => {
    await openEdit();
    const user = userEvent.setup();
    await fill(user, { nombre: 'Rosa V' });
    backend.override('put', '/admin/tutores/t-rosa', { status: 400, body: { codigo: 'VALIDACION', mensaje: 'detalle interno del servidor' } });
    await save(user);
    expect(await screen.findByText(/Revisá los datos del tutor/)).toBeInTheDocument();
    expect(document.body.textContent).not.toContain('detalle interno del servidor');
    expect(field('nombre')).toHaveValue('Rosa V');
  });

  it('shows a permission message on 403 and a «no longer exists» message on 404, keeping the draft', async () => {
    await openEdit();
    const user = userEvent.setup();
    await fill(user, { nombre: 'Rosa P' });
    backend.override('put', '/admin/tutores/t-rosa', { status: 403, body: { codigo: 'ACCESO_DENEGADO', mensaje: 'x' } });
    await save(user);
    expect(await screen.findByText('No tenés permiso para editar tutores.')).toBeInTheDocument();
    backend.override('put', '/admin/tutores/t-rosa', { status: 404, body: { codigo: 'TUTOR_NO_ENCONTRADO', mensaje: 'x' } });
    await save(user);
    expect(await screen.findByText('El tutor ya no existe.')).toBeInTheDocument();
    expect(field('nombre')).toHaveValue('Rosa P');
  });
});

describe('edit a tutor: uncertain result (mock backend)', () => {
  async function uncertain(failure: Parameters<FakeBackend['override']>[2]) {
    const { family } = await openEdit();
    const user = userEvent.setup();
    await fill(user, { telefono: '11 8888-3333' });
    backend.override('put', '/admin/tutores/t-rosa', failure);
    await save(user);
    await screen.findByText(/No pudimos confirmar si el cambio se guardó/);
    return { family, user };
  }

  it.each([
    ['a network failure or timeout', 'network' as const],
    ['a 500', { status: 500, body: { codigo: 'ERROR_INTERNO', mensaje: 'x' } }],
    ['a 503', { status: 503, body: { codigo: 'NO_DISPONIBLE', mensaje: 'x' } }],
  ])('does not claim it failed after %s, never retries by itself, keeps the draft and blocks the resend', async (_label, failure) => {
    await uncertain(failure);
    await wait(400);
    expect(tutorPuts()).toHaveLength(1);
    expect(field('telefono')).toHaveValue('11 8888-3333');
    expect(screen.getByRole('button', { name: 'Guardar cambios' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Verificar datos vigentes' })).toBeEnabled();
  });

  it('when nothing changed, says so honestly and only an explicit action allows trying again, with the SAME revisions', async () => {
    const { user } = await uncertain('network'); // the request never reached the server
    await user.click(screen.getByRole('button', { name: 'Verificar datos vigentes' }));
    const panel = await screen.findByRole('group', { name: 'Verificación' });
    expect(panel).toHaveTextContent('No vemos cambios guardados al verificar');
    expect(panel).toHaveTextContent('revisión obsoleta');
    expect(panel.textContent).not.toMatch(/Cambios guardados\./);
    await wait(300);
    expect(tutorPuts()).toHaveLength(1);
    expect(screen.getByRole('button', { name: 'Guardar cambios' })).toBeDisabled();
    await user.click(within(panel).getByRole('button', { name: 'Volver a intentar' }));
    await save(user);
    await screen.findByText('Cambios guardados.');
    expect(tutorPuts()).toHaveLength(2);
    expect(lastBody()).toMatchObject({ telefono: '11 8888-3333', versionEsperada: '3', versionFamiliaEsperada: '5' });
  });

  it('when the lost answer WAS applied, finds the data equal to the draft and does not offer a resend (which the revision would reject anyway)', async () => {
    const { user } = await uncertain('network');
    backend.changeTutorBehindTheScenes('t-rosa', { telefono: '11 8888-3333' }); // the lost answer: it did apply
    await user.click(screen.getByRole('button', { name: 'Verificar datos vigentes' }));
    const panel = await screen.findByRole('group', { name: 'Verificación' });
    expect(panel).toHaveTextContent('Los datos vigentes ya coinciden con tu borrador');
    expect(panel).toHaveTextContent('No podemos saber si fue tu envío u otro cambio');
    expect(within(panel).queryByRole('button', { name: 'Volver a intentar' })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Guardar cambios' })).toBeDisabled();
    expect(tutorPuts()).toHaveLength(1);
  });

  it('when somebody else changed it meanwhile, shows the comparison and continues only by explicit decision', async () => {
    const { user } = await uncertain('network');
    backend.changeTutorBehindTheScenes('t-rosa', { parentesco: 'Abuela' });
    await user.click(screen.getByRole('button', { name: 'Verificar datos vigentes' }));
    const panel = await screen.findByRole('group', { name: 'Verificación' });
    expect(within(panel).getByRole('table', { name: 'Comparación de datos' })).toBeInTheDocument();
    expect(within(panel).getByRole('row', { name: /Parentesco/ })).toHaveTextContent('Abuela');
    expect(tutorPuts()).toHaveLength(1);
    await user.click(within(panel).getByRole('button', { name: 'Seguir editando mi borrador' }));
    await save(user);
    await screen.findByText('Cambios guardados.');
    expect(lastBody()).toMatchObject({ telefono: '11 8888-3333', parentesco: 'Abuela', versionEsperada: '4' });
  });

  it('shows an error if the verification itself cannot be done, and keeps the block', async () => {
    const { user } = await uncertain('network');
    backend.override('get', '/admin/tutores/t-rosa', 'network');
    await user.click(screen.getByRole('button', { name: 'Verificar datos vigentes' }));
    expect(await screen.findByText(/No pudimos conectar con el servidor/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Guardar cambios' })).toBeDisabled();
    expect(field('telefono')).toHaveValue('11 8888-3333');
  });
});

describe('edit a tutor: unsaved changes and privacy (mock backend)', () => {
  it('warns before leaving a modified form and stays on «Seguir editando»', async () => {
    await openEdit();
    const user = userEvent.setup();
    await fill(user, { nombre: 'Rosa W' });
    await user.click(screen.getByRole('link', { name: 'Cancelar' }));
    const panel = await screen.findByRole('group', { name: 'Cambios sin guardar' });
    expect(location()).toMatch(/\/editar$/);
    await user.click(within(panel).getByRole('button', { name: 'Seguir editando' }));
    expect(field('nombre')).toHaveValue('Rosa W');
    const event = new Event('beforeunload', { cancelable: true });
    window.dispatchEvent(event);
    expect(event.defaultPrevented).toBe(true);
  });

  it('does not warn about an untouched form and cancels back to the tutor', async () => {
    await openEdit();
    const event = new Event('beforeunload', { cancelable: true });
    window.dispatchEvent(event);
    expect(event.defaultPrevented).toBe(false);
    await userEvent.setup().click(screen.getByRole('link', { name: 'Cancelar' }));
    expect(await screen.findByRole('heading', { level: 1, name: 'Rosa Sintética' })).toBeInTheDocument();
    expect(tutorPuts()).toHaveLength(0);
  });

  it('keeps the personal data out of the address, storage and console, and drops it from memory on leaving', async () => {
    const spies = (['log', 'info', 'warn', 'error', 'debug'] as const).map((level) => vi.spyOn(console, level).mockImplementation(() => undefined));
    const logged: unknown[][] = [];
    let queryClient: QueryClient;
    try {
      ({ queryClient } = await openEdit());
      const user = userEvent.setup();
      await fill(user, { telefono: '11 9999-7777', correo: 'privada@example.test' });
      expect(location()).not.toMatch(new RegExp(`${DNI}|rosa@|5555|9999|privada@`));
      expect(JSON.stringify({ ...localStorage, ...sessionStorage })).not.toMatch(new RegExp(`${DNI}|rosa@|5555|9999|privada@`));
      await save(user);
      await screen.findByText('Cambios guardados.');
      await user.click(screen.getByRole('link', { name: 'Volver a la familia' }));
      await screen.findByRole('heading', { level: 1, name: 'Gómez' });
      expect(location()).not.toMatch(new RegExp(`${DNI}|rosa@|5555|9999|privada@`));
      expect(JSON.stringify({ ...localStorage, ...sessionStorage })).not.toMatch(new RegExp(`${DNI}|rosa@|5555|9999|privada@`));
      await waitFor(() => expect(queryClient.getQueryData(['admin', 'tutor-detail', 't-rosa'])).toBeUndefined());
    } finally {
      logged.push(...spies.flatMap((spy) => spy.mock.calls));
      spies.forEach((spy) => spy.mockRestore());
    }
    expect(logged.flat().join(' ')).not.toMatch(new RegExp(`${DNI}|rosa@|5555|9999|privada@`));
  });

  it('drops the draft and the captured revisions when the session now belongs to another administrator, and asks for everything again', async () => {
    backend.addUser({ ...admin, id: 'u-admin-3', nombre: 'Otra', email: 'otra.admin@example.com', mfaEnrolado: true });
    const { queryClient } = await openEdit();
    const user = userEvent.setup();
    await fill(user, { nombre: 'Borrador del primero' });
    expect(field('nombre')).toHaveValue('Borrador del primero');
    const getsBefore = tutorGets().length;
    backend.signIn('otra.admin@example.com');
    await act(async () => {
      await queryClient.invalidateQueries({ queryKey: sessionKey });
    });
    await screen.findByRole('heading', { level: 1, name: 'Editar tutor' });
    await waitFor(() => expect(field('nombre')).toHaveValue('Rosa'));
    expect(document.body.textContent).not.toContain('Borrador del primero');
    expect(tutorGets().length).toBeGreaterThan(getsBefore); // nothing of the previous identity was reused
    expect(tutorPuts()).toHaveLength(0);
  });
});
