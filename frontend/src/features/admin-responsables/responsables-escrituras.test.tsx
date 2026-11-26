import { cleanup, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { seedAthlete } from '@/features/admin-vinculos/links-fixtures';
import type { FakeBackend } from '@/test/fakeBackend';
import { createBackend, renderApp } from '@/test/render';
import { clearAttempts } from './attempts';

// MOCK backend (src/test/fakeBackend.ts mirrors the alta/type/revocation contract of C2-B, including the idempotent alta).
// Real-backend evidence lives in e2e/responsables-escrituras.real.spec.ts.
let backend: FakeBackend;
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const base = (id: string) => `/admin/deportistas/${id}/vinculos-tutores`;
const posts = (id: string) => backend.requests.filter((r) => r.method === 'post' && r.url === base(id));
const writes = () => backend.requests.filter((r) => r.method !== 'get' && !r.url.startsWith('/auth/'));

function seed(over: { athleteActive?: boolean } = {}) {
  clearAttempts();
  backend.setAltaResponsables(true);
  const athlete = seedAthlete(backend, { activo: over.athleteActive ?? true });
  const family = backend.addFamily('Familia Gómez');
  const link = backend.addAdminLink(family.id, athlete.id, { estado: 'ACTIVO', esPrincipal: true });
  const ana = backend.addTutorBehindTheScenes(family.id, { nombre: 'Ana', apellido: 'Alvarez', dni: '30111222' });
  const beto = backend.addTutorBehindTheScenes(family.id, { nombre: 'Beto', apellido: 'Benitez', dni: '30111333' });
  return { athlete, family, link, ana, beto };
}

function open(id: string) {
  backend.signIn('marcos@example.com');
  return renderApp(`/admin/deportistas/${id}/responsables`);
}

async function openAlta() {
  await screen.findByRole('region', { name: 'Responsables vigentes' });
  await userEvent.click(screen.getByRole('button', { name: 'Agregar responsable' }));
  return screen.findByRole('dialog', { name: 'Agregar responsable' });
}

async function chooseAndContinue(dialog: HTMLElement, name: RegExp) {
  await userEvent.click(await within(dialog).findByRole('radio', { name }));
  await userEvent.click(within(dialog).getByRole('button', { name: 'Continuar' }));
  return within(dialog).findByRole('button', { name: 'Confirmar alta' });
}

beforeEach(() => {
  localStorage.clear();
  sessionStorage.clear();
  backend = createBackend();
  // jsdom has no top layer; real modal focus, Escape and keyboard behavior are checked in Playwright.
  Object.defineProperty(HTMLDialogElement.prototype, 'showModal', { configurable: true, value: function (this: HTMLDialogElement) { this.setAttribute('open', ''); } });
  Object.defineProperty(HTMLDialogElement.prototype, 'close', { configurable: true, value: function (this: HTMLDialogElement) { this.removeAttribute('open'); } });
});
afterEach(() => {
  cleanup();
  Reflect.deleteProperty(HTMLDialogElement.prototype, 'showModal');
  Reflect.deleteProperty(HTMLDialogElement.prototype, 'close');
});

describe('alta: eligible tutors', () => {
  it('offers only active tutors of active families with an ACTIVE link who are not responsables yet, with a masked DNI', async () => {
    const { athlete, ana, beto, family } = seed();
    const inactive = backend.addTutorBehindTheScenes(family.id, { nombre: 'Inés', apellido: 'Inactiva', dni: '30111444' });
    inactive.activo = false;
    backend.addResponsable(athlete.id, family.id, { nombre: 'Beto', apellido: 'Benitez', tutorId: beto.id });
    const revokedFamily = backend.addFamily('Familia Revocada');
    backend.addAdminLink(revokedFamily.id, athlete.id, { estado: 'REVOCADO' });
    backend.addTutorBehindTheScenes(revokedFamily.id, { nombre: 'Rosa', apellido: 'Revocada', dni: '30111555' });
    const offFamily = backend.addFamily('Familia Inactiva');
    backend.addAdminLink(offFamily.id, athlete.id, { estado: 'ACTIVO' });
    offFamily.activa = false;
    backend.addTutorBehindTheScenes(offFamily.id, { nombre: 'Olga', apellido: 'Ofuscada', dni: '30111666' });
    open(athlete.id);

    const dialog = await openAlta();
    const radios = await within(dialog).findAllByRole('radio');
    expect(radios).toHaveLength(1);
    expect(radios[0]).toHaveAccessibleName(/Ana Alvarez/);
    expect(dialog).toHaveTextContent('Familia Gómez');
    expect(dialog).not.toHaveTextContent(/Beto|Inés|Rosa|Olga/);
    expect(document.body).not.toHaveTextContent(/30111222|30111333/);
    expect(ana.id).toBeTruthy();
  });

  it('says so when nobody is eligible and keeps the athlete inactive from adding at all', async () => {
    const { athlete, ana, beto } = seed();
    ana.activo = false;
    beto.activo = false;
    open(athlete.id);
    const dialog = await openAlta();
    expect(await within(dialog).findByText(/No hay tutores elegibles/)).toBeInTheDocument();
    expect(within(dialog).queryByRole('button', { name: 'Continuar' })).not.toBeInTheDocument();
  });

  it('disables the alta for an inactive athlete and explains why', async () => {
    const { athlete } = seed({ athleteActive: false });
    open(athlete.id);
    await screen.findByRole('region', { name: 'Responsables vigentes' });
    expect(screen.getByRole('button', { name: 'Agregar responsable' })).toBeDisabled();
    expect(screen.getByText(/el deportista está inactivo/i)).toBeInTheDocument();
  });
});

describe('alta: confirmation, key and outcomes', () => {
  it('sends the observed revisions and ONE Idempotency-Key, leaves the type undeclared by default and refreshes the list', async () => {
    const { athlete, ana } = seed();
    open(athlete.id);
    const dialog = await openAlta();
    await userEvent.click(await within(dialog).findByRole('radio', { name: /Ana Alvarez/ }));
    await userEvent.click(within(dialog).getByRole('button', { name: 'Continuar' }));
    await within(dialog).findByRole('button', { name: 'Confirmar alta' });
    expect(within(dialog).getByLabelText('Tipo de responsable (opcional)')).toHaveValue('');
    await userEvent.click(within(dialog).getByRole('button', { name: 'Confirmar alta' }));

    expect(await screen.findByText('Responsable agregado.')).toBeInTheDocument();
    const [sent] = posts(athlete.id);
    expect(sent!.idempotencyKey).toMatch(UUID);
    expect(sent!.body).toEqual({ tutorId: ana.id, versionFamilia: '0', versionDeportista: '987654321', versionTutor: '0', versionVinculos: '987654321' });
    const section = screen.getByRole('region', { name: 'Responsables vigentes' });
    const item = (await within(section).findByText('Ana Alvarez')).closest('li')!;
    expect(item).toHaveTextContent('Sin declarar');
    expect(item).not.toHaveTextContent('Sin determinar');
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('declares a type when asked, and SIN_DETERMINAR is sent as its own value', async () => {
    const { athlete } = seed();
    open(athlete.id);
    const dialog = await openAlta();
    await chooseAndContinue(dialog, /Ana Alvarez/);
    await userEvent.selectOptions(within(dialog).getByLabelText('Tipo de responsable (opcional)'), 'Sin determinar');
    await userEvent.click(within(dialog).getByRole('button', { name: 'Confirmar alta' }));
    await screen.findByText('Responsable agregado.');
    expect(posts(athlete.id)[0]!.body).toMatchObject({ tipo: 'SIN_DETERMINAR' });
    const item = (await screen.findByText('Ana Alvarez')).closest('li')!;
    expect(item).toHaveTextContent('Sin determinar');
  });

  it('a double click and the keyboard send exactly one request', async () => {
    const { athlete } = seed();
    open(athlete.id);
    const dialog = await openAlta();
    const confirm = await chooseAndContinue(dialog, /Ana Alvarez/);
    confirm.focus();
    await userEvent.dblClick(confirm);
    await screen.findByText('Responsable agregado.');
    expect(posts(athlete.id)).toHaveLength(1);
  });

  it('with the alta disabled shows an informational message, creates nothing and a later confirmation uses a NEW key', async () => {
    const { athlete } = seed();
    backend.setAltaResponsables(false);
    open(athlete.id);
    let dialog = await openAlta();
    await userEvent.click(await within(dialog).findByRole('radio', { name: /Ana Alvarez/ }));
    await userEvent.click(within(dialog).getByRole('button', { name: 'Continuar' }));
    await userEvent.click(await within(dialog).findByRole('button', { name: 'Confirmar alta' }));
    expect(await within(dialog).findByText(/no está habilitada por el momento/)).toBeInTheDocument();
    expect(backend.responsablesOf(athlete.id)).toHaveLength(0);
    await userEvent.click(within(dialog).getByRole('button', { name: 'Cancelar' }));

    backend.setAltaResponsables(true);
    dialog = await openAlta();
    await chooseAndContinue(dialog, /Ana Alvarez/);
    await userEvent.click(within(dialog).getByRole('button', { name: 'Confirmar alta' }));
    await screen.findByText('Responsable agregado.');
    const keys = posts(athlete.id).map((r) => r.idempotencyKey);
    expect(keys).toHaveLength(2);
    expect(keys[0]).not.toEqual(keys[1]);
  });

  it('a stale revision creates nothing, asks to choose again and the next confirmation gets a new key', async () => {
    const { athlete, link } = seed();
    open(athlete.id);
    const dialog = await openAlta();
    const confirm = await chooseAndContinue(dialog, /Ana Alvarez/);
    backend.changeAdminLinkBehindTheScenes(link.id, { esPrincipal: false }); // the links of the athlete moved on
    await userEvent.click(confirm);
    expect(await within(dialog).findByText(/Los datos cambiaron/)).toBeInTheDocument();
    expect(backend.responsablesOf(athlete.id)).toHaveLength(0);
    await userEvent.click(within(dialog).getByRole('button', { name: 'Volver a elegir' }));
    await chooseAndContinue(dialog, /Ana Alvarez/);
    await userEvent.click(within(dialog).getByRole('button', { name: 'Confirmar alta' }));
    await screen.findByText('Responsable agregado.');
    const keys = posts(athlete.id).map((r) => r.idempotencyKey);
    expect(keys[0]).not.toEqual(keys[1]);
  });

  it('a lost answer keeps the SAME key through closing the dialog; retrying the same request creates exactly one responsable', async () => {
    const { athlete } = seed();
    backend.override('post', base(athlete.id), 'lost', { once: true });
    open(athlete.id);
    let dialog = await openAlta();
    await userEvent.click(await within(dialog).findByRole('radio', { name: /Ana Alvarez/ }));
    await userEvent.click(within(dialog).getByRole('button', { name: 'Continuar' }));
    await userEvent.click(await within(dialog).findByRole('button', { name: 'Confirmar alta' }));
    expect(await within(dialog).findByText(/No pudimos confirmar si el responsable se agregó/)).toBeInTheDocument();
    expect(backend.responsablesOf(athlete.id)).toHaveLength(1); // the server did commit it
    await userEvent.click(within(dialog).getByRole('button', { name: 'Cancelar' }));

    // Reopening goes straight to the recovery of THAT request: no new choice, no new key.
    dialog = await openAlta();
    expect(within(dialog).queryByRole('radio')).not.toBeInTheDocument();
    expect(await within(dialog).findByText(/No pudimos confirmar si el responsable se agregó/)).toBeInTheDocument();
    await userEvent.click(within(dialog).getByRole('button', { name: 'Reintentar la misma solicitud' }));
    await screen.findByText('Responsable agregado.');
    const sent = posts(athlete.id);
    expect(sent).toHaveLength(2);
    expect(sent[1]!.idempotencyKey).toBe(sent[0]!.idempotencyKey);
    expect(sent[1]!.body).toEqual(sent[0]!.body);
    expect(backend.responsablesOf(athlete.id)).toHaveLength(1);
    // Resolved: the next alta starts clean, with a fresh choice.
    const next = await openAlta();
    expect(await within(next).findAllByRole('radio')).toHaveLength(1);
    expect(within(next).getByRole('radio')).toHaveAccessibleName(/Beto Benitez/);
  });

  it('retrying after a lost answer still succeeds when the type was edited meanwhile: the reply is the original as it is now', async () => {
    const { athlete } = seed();
    backend.override('post', base(athlete.id), 'lost', { once: true });
    open(athlete.id);
    let dialog = await openAlta();
    await chooseAndContinue(dialog, /Ana Alvarez/);
    await userEvent.selectOptions(within(dialog).getByLabelText('Tipo de responsable (opcional)'), 'Madre');
    await userEvent.click(within(dialog).getByRole('button', { name: 'Confirmar alta' }));
    expect(await within(dialog).findByText(/No pudimos confirmar si el responsable se agregó/)).toBeInTheDocument();
    backend.responsablesOf(athlete.id)[0]!.tipo = 'PADRE'; // another administrator edited the type after the real commit
    await userEvent.click(within(dialog).getByRole('button', { name: 'Reintentar la misma solicitud' }));
    expect(await screen.findByText('Responsable agregado.')).toBeInTheDocument();
    expect(backend.responsablesOf(athlete.id)).toHaveLength(1);
    dialog = await openAlta();
    expect(await within(dialog).findAllByRole('radio')).toHaveLength(1); // resolved: the next alta starts clean
  });

  it('consulting after a lost answer finds the responsable, resolves the pending request and sends nothing more', async () => {
    const { athlete } = seed();
    backend.override('post', base(athlete.id), 'lost', { once: true });
    open(athlete.id);
    const dialog = await openAlta();
    await chooseAndContinue(dialog, /Ana Alvarez/);
    await userEvent.click(within(dialog).getByRole('button', { name: 'Confirmar alta' }));
    await userEvent.click(await within(dialog).findByRole('button', { name: 'Consultar responsables vigentes' }));
    expect(await screen.findByText(/ya figura en la lista/)).toBeInTheDocument();
    expect(posts(athlete.id)).toHaveLength(1);
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('when the server was NOT reached, retrying the same request creates it once and with the same key', async () => {
    const { athlete } = seed();
    backend.override('post', base(athlete.id), 'network', { once: true });
    open(athlete.id);
    const dialog = await openAlta();
    await chooseAndContinue(dialog, /Ana Alvarez/);
    await userEvent.click(within(dialog).getByRole('button', { name: 'Confirmar alta' }));
    await userEvent.click(await within(dialog).findByRole('button', { name: 'Reintentar la misma solicitud' }));
    await screen.findByText('Responsable agregado.');
    const sent = posts(athlete.id);
    expect(sent[0]!.idempotencyKey).toBe(sent[1]!.idempotencyKey);
    expect(backend.responsablesOf(athlete.id)).toHaveLength(1);
  });

  it('a concurrency conflict allows repeating the same request with the same key', async () => {
    const { athlete } = seed();
    backend.override('post', base(athlete.id), { status: 409, body: { codigo: 'CONFLICTO_CONCURRENCIA', mensaje: 'x' } }, { once: true });
    open(athlete.id);
    const dialog = await openAlta();
    await chooseAndContinue(dialog, /Ana Alvarez/);
    await userEvent.click(within(dialog).getByRole('button', { name: 'Confirmar alta' }));
    expect(await within(dialog).findByText(/Otra operación estaba en curso/)).toBeInTheDocument();
    await userEvent.click(within(dialog).getByRole('button', { name: 'Reintentar la misma solicitud' }));
    await screen.findByText('Responsable agregado.');
    const sent = posts(athlete.id);
    expect(sent[0]!.idempotencyKey).toBe(sent[1]!.idempotencyKey);
  });
});

describe('edit the declared type and revoke', () => {
  function withOne() {
    const s = seed();
    const row = backend.addResponsable(s.athlete.id, s.family.id, { nombre: 'Ana', apellido: 'Alvarez', tipo: 'MADRE', tutorId: s.ana.id });
    return { ...s, row };
  }

  it('changes the type with the revision of the row, forbids saving the same one and tells NULL from SIN_DETERMINAR', async () => {
    const { athlete, row } = withOne();
    open(athlete.id);
    await userEvent.click(await screen.findByRole('button', { name: 'Cambiar tipo: Ana Alvarez' }));
    const dialog = await screen.findByRole('dialog', { name: 'Cambiar el tipo declarado' });
    expect(within(dialog).getByRole('button', { name: 'Guardar tipo' })).toBeDisabled();
    await userEvent.selectOptions(within(dialog).getByLabelText('Tipo de responsable'), 'Sin declarar');
    await userEvent.click(within(dialog).getByRole('button', { name: 'Guardar tipo' }));
    expect(await screen.findByText('Tipo actualizado.')).toBeInTheDocument();
    const put = backend.requests.find((r) => r.method === 'put')!;
    expect(put.url).toBe(`${base(athlete.id)}/${row.id}`);
    expect(put.body).toEqual({ tipo: null, versionEsperada: '0' });
    const item = (await screen.findByText('Ana Alvarez')).closest('li')!;
    expect(item).toHaveTextContent('Sin declarar');
    expect(item).not.toHaveTextContent('Sin determinar');
  });

  it('a stale type edit applies nothing, refreshes and says so', async () => {
    const { athlete, row } = withOne();
    open(athlete.id);
    await userEvent.click(await screen.findByRole('button', { name: 'Cambiar tipo: Ana Alvarez' }));
    const dialog = await screen.findByRole('dialog', { name: 'Cambiar el tipo declarado' });
    row.version += 1; // another administrator changed it meanwhile
    await userEvent.selectOptions(within(dialog).getByLabelText('Tipo de responsable'), 'Padre');
    await userEvent.click(within(dialog).getByRole('button', { name: 'Guardar tipo' }));
    expect(await within(dialog).findByText(/cambió mientras decidías/)).toBeInTheDocument();
    expect(row.tipo).toBe('MADRE');
  });

  it('revoking explains it is irreversible, cancelling writes nothing and confirming closes it for good', async () => {
    const { athlete } = withOne();
    open(athlete.id);
    await userEvent.click(await screen.findByRole('button', { name: 'Revocar: Ana Alvarez' }));
    let dialog = await screen.findByRole('dialog', { name: 'Revocar responsable' });
    expect(dialog).toHaveTextContent(/irreversible/);
    expect(within(dialog).getByRole('button', { name: 'Cancelar' })).toHaveFocus();
    await userEvent.click(within(dialog).getByRole('button', { name: 'Cancelar' }));
    expect(writes()).toEqual([]);
    expect(screen.getByRole('button', { name: 'Revocar: Ana Alvarez' })).toHaveFocus();

    await userEvent.click(screen.getByRole('button', { name: 'Revocar: Ana Alvarez' }));
    dialog = await screen.findByRole('dialog', { name: 'Revocar responsable' });
    await userEvent.click(within(dialog).getByRole('button', { name: 'Confirmar revocación' }));
    expect(await screen.findByText('Responsable revocado.')).toBeInTheDocument();
    await waitFor(() => expect(within(screen.getByRole('region', { name: 'Responsables vigentes' })).queryByText('Ana Alvarez')).not.toBeInTheDocument());
    await userEvent.click(screen.getByRole('button', { name: 'Ver historial' }));
    const closed = (await within(screen.getByRole('region', { name: 'Historial' })).findByText('Ana Alvarez')).closest('li')!;
    expect(closed).toHaveTextContent('Revocada por el club');
    expect(within(closed).queryByRole('button')).not.toBeInTheDocument();
  });

  it('a lost answer on revoke is verified by consulting and NEVER repeated', async () => {
    const { athlete, row } = withOne();
    backend.override('post', `${base(athlete.id)}/${row.id}/revocar`, 'lost', { once: true });
    open(athlete.id);
    await userEvent.click(await screen.findByRole('button', { name: 'Revocar: Ana Alvarez' }));
    const dialog = await screen.findByRole('dialog', { name: 'Revocar responsable' });
    await userEvent.click(within(dialog).getByRole('button', { name: 'Confirmar revocación' }));
    expect(await within(dialog).findByText(/No podemos confirmar el resultado/)).toBeInTheDocument();
    expect(within(dialog).queryByRole('button', { name: 'Confirmar revocación' })).not.toBeInTheDocument();
    await userEvent.click(within(dialog).getByRole('button', { name: 'Consultar responsables vigentes' }));
    expect(await screen.findByText(/Estado vigente consultado/)).toBeInTheDocument();
    expect(backend.requests.filter((r) => r.url.endsWith('/revocar'))).toHaveLength(1);
    expect(backend.responsablesOf(athlete.id)[0]!.cerradoEn).not.toBeNull();
  });
});
