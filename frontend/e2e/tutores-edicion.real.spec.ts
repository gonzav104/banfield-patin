import { randomBytes } from 'node:crypto';
import { expect, test, type Browser, type BrowserContext, type Page } from '@playwright/test';
import { dbQuery, hasDisposableDb } from './support/db';
import { AdminSeed, csrfHeaders, loginAdminUi, type SeededFamily } from './support/seed';

/**
 * REAL flows of the administrative EDIT of a tutor (FE-05 / U5-B): browser -> Vite proxy -> Spring backend -> PostgreSQL, against the
 * two-revision contract of FE-05/U4 (PUT /api/admin/tutores/{id} with versionEsperada and versionFamiliaEsperada). Requires the
 * administrator enrolled by auth.real.spec.ts (secret in e2e/.state.json). Everything is synthetic and created through the real admin
 * API. The «other administrator» of the conflict tests is the same administrator writing through the API while the browser holds older
 * revisions: for the backend that is the same thing (revisions that moved on). School isolation between two schools is covered by the
 * backend DB tests.
 *
 * Only the tests labelled "simulated outage" use page.route. No traces, screenshots or videos are recorded (see playwright.config.ts):
 * names, DNIs, phones and e-mails never reach artifacts.
 */
test.describe.configure({ mode: 'serial' });

const suffix = randomBytes(3).toString('hex');
const familyName = (label: string) => `E2E Edicion ${label} ${suffix}`;

const PHONE = '11 4444-0187';
const EMAIL = `edicion.${suffix}@example.test`;
const NEW_PHONE = '11 6666-0101';

let browserRef: Browser;
let adminContext: BrowserContext;
let seed: AdminSeed;
let intruder: SeededFamily;
const fam: Record<string, string> = {};
const tut: Record<string, { id: string; dni: string | null }> = {};
const dnis: string[] = [];

interface StoredTutor {
  id: string;
  familiaId: string;
  nombre: string;
  apellido: string;
  dni: string | null;
  telefono: string | null;
  email: string | null;
  parentesco: string | null;
  version: string;
}
interface StoredFamily {
  nombreReferencia: string;
  activa: boolean;
  version: string;
}

async function adminPage(): Promise<{ context: BrowserContext; page: Page }> {
  const context = await browserRef.newContext({ baseURL: 'http://127.0.0.1:5173' });
  const page = await context.newPage();
  await loginAdminUi(page);
  return { context, page };
}

/** What the backend really has, read through the admin API. */
async function storedTutor(id: string): Promise<StoredTutor> {
  const response = await adminContext.request.get(`/api/admin/tutores/${id}`);
  expect(response.ok()).toBe(true);
  return (await response.json()) as StoredTutor;
}

async function storedFamily(id: string): Promise<StoredFamily> {
  const response = await adminContext.request.get(`/api/admin/familias/${id}`);
  expect(response.ok()).toBe(true);
  return (await response.json()) as StoredFamily;
}

const nextVersion = (version: string) => String(Number(version) + 1);

/** «Another administrator» edits the tutor (a COMPLETE replacement through the API): the tutor revision moves on. */
async function otherAdminEditsTutor(id: string, patch: Partial<Pick<StoredTutor, 'nombre' | 'apellido' | 'telefono' | 'email' | 'parentesco' | 'dni'>>): Promise<void> {
  const current = await storedTutor(id);
  const family = await storedFamily(current.familiaId);
  const response = await adminContext.request.put(`/api/admin/tutores/${id}`, {
    data: {
      nombre: current.nombre,
      apellido: current.apellido,
      dni: current.dni,
      telefono: current.telefono,
      email: current.email,
      parentesco: current.parentesco,
      ...patch,
      versionEsperada: current.version,
      versionFamiliaEsperada: family.version,
    },
    headers: await csrfHeaders(adminContext.request),
  });
  expect(response.status()).toBe(200);
}

/** «Another administrator» renames the family: the family revision moves on. */
async function otherAdminRenames(id: string, nombreReferencia: string): Promise<void> {
  const family = await storedFamily(id);
  const response = await adminContext.request.put(`/api/admin/familias/${id}`, {
    data: { nombreReferencia, versionEsperada: family.version },
    headers: await csrfHeaders(adminContext.request),
  });
  expect(response.status()).toBe(200);
}

async function otherAdminDeactivates(id: string): Promise<void> {
  const family = await storedFamily(id);
  const response = await adminContext.request.post(`/api/admin/familias/${id}/desactivar`, {
    data: { versionEsperada: family.version },
    headers: await csrfHeaders(adminContext.request),
  });
  expect(response.status()).toBe(200);
}

const noHorizontalOverflow = (page: Page) => page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth);
const label = (page: Page, pattern: RegExp) => page.getByLabel(pattern);

/** Records the body of every tutor edit the browser sends (to prove which revisions it sent). */
function recordUpdates(page: Page, tutorId: string): Record<string, unknown>[] {
  const bodies: Record<string, unknown>[] = [];
  page.on('request', (request) => {
    if (request.method() === 'PUT' && new URL(request.url()).pathname === `/api/admin/tutores/${tutorId}`) {
      bodies.push(JSON.parse(request.postData() ?? '{}') as Record<string, unknown>);
    }
  });
  return bodies;
}

async function openEdit(page: Page, key: string): Promise<void> {
  await page.goto(`/admin/familias/${fam[key]}/tutores/${tut[key]!.id}/editar`);
  await expect(page.getByRole('heading', { level: 1, name: 'Editar tutor' })).toBeVisible();
  await expect(label(page, /^Nombre/)).toHaveValue('Rosa');
}

const save = (page: Page) => page.getByRole('button', { name: 'Guardar cambios' });

function countOf(sql: string, key: string): number | null {
  if (!hasDisposableDb()) return null;
  const match = new RegExp(`${key}=(\\d+)`).exec(dbQuery(sql));
  return match ? Number(match[1]) : null;
}
const accounts = () => countOf("SELECT 'USERS=' || count(*) FROM gestion_patin.usuario;", 'USERS');
const invitations = () => countOf("SELECT 'INV=' || count(*) FROM gestion_patin.invitacion;", 'INV');

const KEYS = ['basico', 'limpiar', 'doble', 'tutorObsoleto', 'segundoConflicto', 'familiaObsoleta', 'inactivaDespues', 'inactivaAlCargar', 'perdida', 'noLlego', 'permisos', 'movil'];

test.beforeAll(async ({ browser }) => {
  browserRef = browser;
  adminContext = await browser.newContext({ baseURL: 'http://127.0.0.1:5173' });
  await loginAdminUi(await adminContext.newPage());
  seed = new AdminSeed(adminContext.request);

  for (const key of KEYS) {
    fam[key] = (await seed.createFamily(familyName(key))).id;
    const created = await seed.createTutor(fam[key], { nombre: 'Rosa', apellido: 'Sintetica', telefono: PHONE, email: EMAIL, parentesco: 'Madre' });
    tut[key] = created;
    if (created.dni) dnis.push(created.dni);
  }
  await seed.deactivateFamily(fam.inactivaAlCargar!, (await storedFamily(fam.inactivaAlCargar!)).version); // the revision is read, never assumed
  const anonymous = await browser.newContext({ baseURL: 'http://127.0.0.1:5173' });
  intruder = await seed.createFamilyWithUser(anonymous.request, familyName('usuaria'), {
    nombre: 'Ines',
    apellido: 'Intrusa',
    email: `edicion.usuaria.${suffix}@example.test`,
    password: `Fam-${randomBytes(8).toString('hex')}`,
  });
  await anonymous.close();
});

test.afterAll(async () => {
  await adminContext.close();
});

test.describe('edit a tutor (real backend)', () => {
  test('opens from the tutor detail, starts from the FULL data, saves with BOTH captured revisions and changes only the tutor revision; no accounts or invitations; nothing leaks', async () => {
    const usersBefore = accounts();
    const invitationsBefore = invitations();
    const tutorBefore = await storedTutor(tut.basico!.id);
    const familyBefore = await storedFamily(fam.basico!);
    const { context, page } = await adminPage();
    const bodies = recordUpdates(page, tut.basico!.id);
    const consoleText: string[] = [];
    page.on('console', (message) => consoleText.push(message.text()));

    await page.goto(`/admin/familias/${fam.basico}`);
    await page.getByRole('list', { name: 'Tutores' }).getByRole('link', { name: 'Rosa Sintetica' }).click();
    await page.getByRole('link', { name: 'Editar tutor' }).click();
    await expect(page).toHaveURL(new RegExp(`/admin/familias/${fam.basico}/tutores/${tut.basico!.id}/editar$`));
    await expect(page.getByRole('heading', { level: 1, name: 'Editar tutor' })).toBeVisible();
    // every value comes from the individual detail, including the FULL DNI that the family summary only carries masked
    await expect(label(page, /^DNI/)).toHaveValue(tut.basico!.dni as string);
    await expect(label(page, /^Teléfono/)).toHaveValue(PHONE);
    await expect(label(page, /^Correo/)).toHaveValue(EMAIL);
    await expect(label(page, /^Parentesco/)).toHaveValue('Madre');
    await expect(save(page)).toBeDisabled(); // nothing to save yet

    await label(page, /^Teléfono/).fill(NEW_PHONE);
    await save(page).click();
    await expect(page).toHaveURL(new RegExp(`/admin/familias/${fam.basico}/tutores/${tut.basico!.id}$`));
    await expect(page.getByText('Cambios guardados.')).toBeVisible();
    await expect(page.getByText(NEW_PHONE, { exact: true })).toBeVisible();

    expect(bodies).toHaveLength(1);
    expect(bodies[0]).toMatchObject({ telefono: NEW_PHONE, versionEsperada: tutorBefore.version, versionFamiliaEsperada: familyBefore.version });
    expect(typeof bodies[0]!.versionEsperada).toBe('string');
    const tutorAfter = await storedTutor(tut.basico!.id);
    expect(tutorAfter).toMatchObject({ telefono: NEW_PHONE, dni: tutorBefore.dni, email: EMAIL, parentesco: 'Madre', familiaId: fam.basico });
    expect(tutorAfter.version).toBe(nextVersion(tutorBefore.version));
    expect((await storedFamily(fam.basico!)).version).toBe(familyBefore.version); // editing a tutor does not move the family revision
    if (usersBefore !== null) expect(accounts()).toBe(usersBefore);
    if (invitationsBefore !== null) expect(invitations()).toBe(invitationsBefore);

    for (const value of [...dnis, PHONE, NEW_PHONE, EMAIL, '4444-0187']) {
      expect(page.url()).not.toContain(value);
      expect(consoleText.join('\n')).not.toContain(value);
      expect(await page.evaluate(() => JSON.stringify({ ...localStorage, ...sessionStorage }))).not.toContain(value);
    }
    await context.close();
  });

  test('complete replacement: clearing optional fields stores them as null (explicit), says which saved data will be removed, and keeps the rest', async () => {
    const before = await storedTutor(tut.limpiar!.id);
    const { context, page } = await adminPage();
    const bodies = recordUpdates(page, tut.limpiar!.id);
    await openEdit(page, 'limpiar');
    await label(page, /^Teléfono/).fill('');
    await label(page, /^Parentesco/).fill('');
    await expect(page.getByText(/Al guardar se van a quitar estos datos: Teléfono, Parentesco\./)).toBeVisible();
    expect(await page.locator('body').innerText()).not.toContain(PHONE);
    await save(page).click();
    await expect(page.getByText('Cambios guardados.')).toBeVisible();
    expect(bodies[0]).toMatchObject({ telefono: null, parentesco: null, dni: before.dni, email: EMAIL });
    const after = await storedTutor(tut.limpiar!.id);
    expect(after).toMatchObject({ telefono: null, parentesco: null, dni: before.dni, email: EMAIL, nombre: 'Rosa', apellido: 'Sintetica' });
    await context.close();
  });

  test('validation stops the request, and a double click sends one single request', async () => {
    const before = await storedTutor(tut.doble!.id);
    const { context, page } = await adminPage();
    const bodies = recordUpdates(page, tut.doble!.id);
    await openEdit(page, 'doble');
    await label(page, /^Nombre/).fill('');
    await save(page).click();
    await expect(page.getByText('Ingresá el nombre.')).toBeVisible();
    expect(bodies).toHaveLength(0);
    await label(page, /^Nombre/).fill('Doble');
    await save(page).dblclick();
    await expect(page.getByText('Cambios guardados.')).toBeVisible();
    expect(bodies).toHaveLength(1);
    expect((await storedTutor(tut.doble!.id)).version).toBe(nextVersion(before.version)); // written once
    await context.close();
  });
});

test.describe('concurrent changes (real backend)', () => {
  test('tutor modified by another administrator: 409, the draft is kept, nothing is overwritten, the comparison is explicit and continuing merges only what was not touched', async () => {
    const { context, page } = await adminPage();
    const bodies = recordUpdates(page, tut.tutorObsoleto!.id);
    await openEdit(page, 'tutorObsoleto');
    await label(page, /^Teléfono/).fill(NEW_PHONE);
    await otherAdminEditsTutor(tut.tutorObsoleto!.id, { telefono: '11 3333-9090', parentesco: 'Abuela' });
    const afterOther = await storedTutor(tut.tutorObsoleto!.id);

    await save(page).click();
    await expect(page.getByRole('alert')).toContainText('El tutor o su familia fueron modificados');
    await expect(label(page, /^Teléfono/)).toHaveValue(NEW_PHONE);
    await expect(save(page)).toBeDisabled();
    await page.waitForTimeout(800);
    expect(bodies).toHaveLength(1); // no automatic retry, no forced save
    expect(await storedTutor(tut.tutorObsoleto!.id)).toMatchObject({ telefono: '11 3333-9090', parentesco: 'Abuela', version: afterOther.version });

    await page.getByRole('button', { name: 'Ver datos vigentes' }).click();
    const panel = page.getByRole('group', { name: 'Datos vigentes' });
    const phoneRow = panel.getByRole('row', { name: /Teléfono/ });
    await expect(phoneRow).toContainText('11 3333-9090');
    await expect(phoneRow).toContainText(NEW_PHONE);
    await expect(phoneRow).toContainText('distinto');
    await expect(panel.getByRole('row', { name: /Parentesco/ })).toContainText('Abuela');
    expect(bodies).toHaveLength(1);

    await panel.getByRole('button', { name: 'Seguir editando mi borrador' }).click();
    await expect(label(page, /^Teléfono/)).toHaveValue(NEW_PHONE); // changed by the administrator: the draft is kept
    await expect(label(page, /^Parentesco/)).toHaveValue('Abuela'); // not touched: the other administrator's change is NOT undone
    expect(bodies).toHaveLength(1); // continuing saves nothing
    await save(page).click();
    await expect(page.getByText('Cambios guardados.')).toBeVisible();
    expect(bodies).toHaveLength(2);
    expect(bodies[1]).toMatchObject({ telefono: NEW_PHONE, parentesco: 'Abuela', versionEsperada: afterOther.version });
    expect(await storedTutor(tut.tutorObsoleto!.id)).toMatchObject({ telefono: NEW_PHONE, parentesco: 'Abuela', version: nextVersion(afterOther.version) });
    await context.close();
  });

  test('a new concurrent change after continuing produces a new conflict, and discarding drops the draft', async () => {
    const { context, page } = await adminPage();
    const bodies = recordUpdates(page, tut.segundoConflicto!.id);
    await openEdit(page, 'segundoConflicto');
    await label(page, /^Nombre/).fill('Rosa Dos');
    await otherAdminEditsTutor(tut.segundoConflicto!.id, { parentesco: 'Tía' });
    await save(page).click();
    await expect(page.getByRole('alert')).toContainText('El tutor o su familia fueron modificados');
    await page.getByRole('button', { name: 'Ver datos vigentes' }).click();
    await page.getByRole('group', { name: 'Datos vigentes' }).getByRole('button', { name: 'Seguir editando mi borrador' }).click();
    await otherAdminEditsTutor(tut.segundoConflicto!.id, { apellido: 'Otra' }); // while the draft is being completed
    const afterSecond = await storedTutor(tut.segundoConflicto!.id);
    await save(page).click();
    await expect(page.getByRole('alert')).toContainText('El tutor o su familia fueron modificados');
    expect(bodies).toHaveLength(2);
    expect(await storedTutor(tut.segundoConflicto!.id)).toMatchObject({ nombre: 'Rosa', apellido: 'Otra', version: afterSecond.version });

    await page.getByRole('button', { name: 'Ver datos vigentes' }).click();
    await page.getByRole('group', { name: 'Datos vigentes' }).getByRole('button', { name: 'Descartar mi borrador' }).click();
    await expect(page.getByRole('heading', { level: 1, name: 'Rosa Otra' })).toBeVisible();
    expect(await page.locator('body').innerText()).not.toContain('Rosa Dos');
    await context.close();
  });

  test('family modified by another administrator: 409 even though the tutor did not change, and continuing sends the NEW family revision', async () => {
    const { context, page } = await adminPage();
    const bodies = recordUpdates(page, tut.familiaObsoleta!.id);
    await openEdit(page, 'familiaObsoleta');
    const tutorBefore = await storedTutor(tut.familiaObsoleta!.id);
    const familyBefore = await storedFamily(fam.familiaObsoleta!);
    await label(page, /^Parentesco/).fill('Tutora legal');
    await otherAdminRenames(fam.familiaObsoleta!, `${familyName('familiaObsoleta')} renombrada`);
    const familyAfter = await storedFamily(fam.familiaObsoleta!);

    await save(page).click();
    await expect(page.getByRole('alert')).toContainText('El tutor o su familia fueron modificados');
    expect(bodies[0]).toMatchObject({ versionEsperada: tutorBefore.version, versionFamiliaEsperada: familyBefore.version });
    expect((await storedTutor(tut.familiaObsoleta!.id)).parentesco).toBe('Madre'); // nothing was applied
    await page.getByRole('button', { name: 'Ver datos vigentes' }).click();
    const panel = page.getByRole('group', { name: 'Datos vigentes' });
    await expect(panel).toContainText('renombrada');
    await panel.getByRole('button', { name: 'Seguir editando mi borrador' }).click();
    await save(page).click();
    await expect(page.getByText('Cambios guardados.')).toBeVisible();
    expect(bodies[1]).toMatchObject({ parentesco: 'Tutora legal', versionEsperada: tutorBefore.version, versionFamiliaEsperada: familyAfter.version });
    expect((await storedFamily(fam.familiaObsoleta!)).version).toBe(familyAfter.version); // editing a tutor does not move the family revision
    await context.close();
  });

  test('family deactivated while editing: FAMILIA_INACTIVA is told apart from a stale revision, there is no way to continue and nothing is saved', async () => {
    const { context, page } = await adminPage();
    const bodies = recordUpdates(page, tut.inactivaDespues!.id);
    await openEdit(page, 'inactivaDespues');
    await label(page, /^Nombre/).fill('Rosa Q');
    await otherAdminDeactivates(fam.inactivaDespues!);
    await save(page).click();
    const alert = page.getByRole('alert');
    await expect(alert).toContainText('La familia está inactiva: no se pueden editar sus tutores');
    await expect(alert).not.toContainText('fueron modificados');
    await expect(label(page, /^Nombre/)).toHaveValue('Rosa Q');
    await expect(save(page)).toBeDisabled();
    await page.getByRole('button', { name: 'Ver datos vigentes' }).click();
    const panel = page.getByRole('group', { name: 'Datos vigentes' });
    await expect(panel).toContainText('Inactiva');
    await expect(panel.getByRole('button', { name: 'Seguir editando mi borrador' })).toHaveCount(0);
    expect(bodies).toHaveLength(1);
    expect((await storedTutor(tut.inactivaDespues!.id)).nombre).toBe('Rosa');
    await context.close();
  });

  test('an inactive family does not open the form', async () => {
    const { context, page } = await adminPage();
    const bodies = recordUpdates(page, tut.inactivaAlCargar!.id);
    await page.goto(`/admin/familias/${fam.inactivaAlCargar}/tutores/${tut.inactivaAlCargar!.id}/editar`);
    await expect(page.getByText(/La familia está inactiva: no se pueden editar sus tutores/)).toBeVisible();
    await expect(label(page, /^Nombre/)).toHaveCount(0);
    expect(bodies).toHaveLength(0);
    await context.close();
  });
});

test.describe('uncertain result (real backend, simulated outage)', () => {
  test('simulated outage, answer lost AFTER the backend applied the edit: no retry, the verification finds the data equal to the draft and offers no resend', async () => {
    const before = await storedTutor(tut.perdida!.id);
    const { context, page } = await adminPage();
    let attempts = 0;
    await page.route(`**/api/admin/tutores/${tut.perdida!.id}`, async (route) => {
      if (route.request().method() !== 'PUT') return route.continue();
      attempts += 1;
      await route.fetch(); // the request DOES reach the backend, which applies the edit...
      return route.abort('failed'); // ...but the browser never sees the answer
    });
    await openEdit(page, 'perdida');
    await label(page, /^Teléfono/).fill(NEW_PHONE);
    await save(page).click();
    await expect(page.getByRole('alert')).toContainText('No pudimos confirmar si el cambio se guardó');
    await page.waitForTimeout(1_000);
    expect(attempts).toBe(1); // never retried by itself
    await expect(save(page)).toBeDisabled();
    expect(await storedTutor(tut.perdida!.id)).toMatchObject({ telefono: NEW_PHONE, version: nextVersion(before.version) }); // it WAS applied

    await page.getByRole('button', { name: 'Verificar datos vigentes' }).click();
    const panel = page.getByRole('group', { name: 'Verificación' });
    await expect(panel).toContainText('Los datos vigentes ya coinciden con tu borrador');
    await expect(panel).toContainText('No podemos saber si fue tu envío u otro cambio');
    await expect(panel.getByRole('button', { name: 'Volver a intentar' })).toHaveCount(0);
    await expect(save(page)).toBeDisabled();
    expect(attempts).toBe(1);
    expect((await storedTutor(tut.perdida!.id)).version).toBe(nextVersion(before.version)); // applied once
    await context.close();
  });

  test('simulated outage, request that never arrived: the verification reports that nothing was saved and an explicit action tries again, once, with the same revisions', async () => {
    const before = await storedTutor(tut.noLlego!.id);
    const { context, page } = await adminPage();
    const bodies = recordUpdates(page, tut.noLlego!.id);
    await page.route(`**/api/admin/tutores/${tut.noLlego!.id}`, async (route) => {
      if (route.request().method() !== 'PUT') return route.continue();
      return route.abort('failed'); // it never reaches the backend
    });
    await openEdit(page, 'noLlego');
    await label(page, /^Teléfono/).fill(NEW_PHONE);
    await save(page).click();
    await expect(page.getByRole('alert')).toContainText('No pudimos confirmar si el cambio se guardó');
    expect((await storedTutor(tut.noLlego!.id)).telefono).toBe(PHONE);

    await page.getByRole('button', { name: 'Verificar datos vigentes' }).click();
    const panel = page.getByRole('group', { name: 'Verificación' });
    await expect(panel).toContainText('No vemos cambios guardados al verificar');
    await expect(panel).toContainText('revisión obsoleta');
    await expect(save(page)).toBeDisabled();
    expect(bodies).toHaveLength(1);
    await page.unroute(`**/api/admin/tutores/${tut.noLlego!.id}`); // the network is back
    await panel.getByRole('button', { name: 'Volver a intentar' }).click();
    await save(page).click();
    await expect(page.getByText('Cambios guardados.')).toBeVisible();
    expect(bodies).toHaveLength(2);
    expect(bodies[1]).toMatchObject({ versionEsperada: before.version, versionFamiliaEsperada: bodies[0]!.versionFamiliaEsperada });
    expect(await storedTutor(tut.noLlego!.id)).toMatchObject({ telefono: NEW_PHONE, version: nextVersion(before.version) });
    await context.close();
  });
});

test.describe('permissions and layout (real backend)', () => {
  test('anonymous, family, CSRF-less and screen access are rejected and change or reveal nothing', async ({ browser, request }) => {
    const before = await storedTutor(tut.permisos!.id);
    const familyBefore = await storedFamily(fam.permisos!);
    const body = {
      nombre: 'Intruso',
      apellido: 'Anonimo',
      versionEsperada: before.version,
      versionFamiliaEsperada: familyBefore.version,
    };
    expect((await request.put(`/api/admin/tutores/${tut.permisos!.id}`, { data: body, headers: await csrfHeaders(request) })).status()).toBe(401);

    const familyContext = await browser.newContext({ baseURL: 'http://127.0.0.1:5173' });
    const page = await familyContext.newPage();
    await page.goto('/login');
    await page.getByLabel(/^Correo electrónico/).fill(intruder.email);
    await page.getByLabel(/^Contraseña/).fill(intruder.password);
    await page.getByRole('button', { name: 'Ingresar' }).click();
    await expect(page).toHaveURL(/\/familia$/);
    const familyHeaders = await csrfHeaders(familyContext.request);
    expect((await familyContext.request.put(`/api/admin/tutores/${tut.permisos!.id}`, { data: { ...body, nombre: 'Intrusa' }, headers: familyHeaders })).status()).toBe(403);
    await page.goto(`/admin/familias/${fam.permisos}/tutores/${tut.permisos!.id}/editar`);
    await expect(page.getByRole('heading', { name: 'No tenés permiso para ver esta sección' })).toBeVisible();
    expect(await page.locator('body').innerText()).not.toContain('Rosa');
    await familyContext.close();

    // The administrator without the CSRF header, and without the revisions the contract requires.
    expect((await adminContext.request.put(`/api/admin/tutores/${tut.permisos!.id}`, { data: { ...body, nombre: 'SinCsrf' } })).status()).toBe(403);
    const withoutRevision = { nombre: 'SinRevision', apellido: 'X' };
    expect((await adminContext.request.put(`/api/admin/tutores/${tut.permisos!.id}`, { data: withoutRevision, headers: await csrfHeaders(adminContext.request) })).status()).toBe(400);
    expect(await storedTutor(tut.permisos!.id)).toEqual(before);
    expect((await storedFamily(fam.permisos!)).version).toBe(familyBefore.version);
  });

  test('the form and the comparison fit a 360 px phone without horizontal scrolling', async () => {
    const { context, page } = await adminPage();
    await page.setViewportSize({ width: 360, height: 740 });
    await openEdit(page, 'movil');
    expect(await noHorizontalOverflow(page)).toBe(true);
    await label(page, /^Nombre/).fill('Nombre bastante largo para forzar el ajuste de línea');
    await label(page, /^Correo/).fill(`un.correo.muy.largo.${suffix}@example.test`);
    expect(await noHorizontalOverflow(page)).toBe(true);
    for (const control of [label(page, /^Nombre/), label(page, /^DNI/), save(page)]) {
      expect((await control.boundingBox())?.height ?? 0).toBeGreaterThanOrEqual(44);
    }
    await otherAdminEditsTutor(tut.movil!.id, { parentesco: 'Abuela materna de la deportista' });
    await save(page).click();
    await page.getByRole('button', { name: 'Ver datos vigentes' }).click();
    await expect(page.getByRole('table', { name: 'Comparación de datos' })).toBeVisible();
    expect(await noHorizontalOverflow(page)).toBe(true);
    await context.close();
  });
});
