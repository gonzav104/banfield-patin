import { randomBytes, randomUUID } from 'node:crypto';
import { expect, test, type Browser, type BrowserContext, type Page } from '@playwright/test';
import { dbQuery, hasDisposableDb } from './support/db';
import { AdminSeed, csrfHeaders, loginAdminUi, syntheticDni, type SeededFamily } from './support/seed';

/**
 * REAL flows of the tutors inside the administrative family screens (FE-05 / U5-A: detail and creation): browser -> Vite proxy ->
 * Spring backend -> PostgreSQL, against the optimistic-revision contract of FE-05/U4. Requires the administrator enrolled by
 * auth.real.spec.ts (secret in e2e/.state.json). Everything is synthetic and created through the real admin API. The «other
 * administrator» of the conflict tests is the same administrator writing through the API while the browser holds an older revision:
 * for the backend that is the same thing (a revision that moved on). School isolation between two schools is covered by the backend
 * DB tests.
 *
 * Only the tests labelled "simulated outage" use page.route. No traces, screenshots or videos are recorded (see playwright.config.ts):
 * names, DNIs, phones and e-mails never reach artifacts.
 */
test.describe.configure({ mode: 'serial' });

const suffix = randomBytes(3).toString('hex');
const familyName = (label: string) => `E2E Tutores ${label} ${suffix}`;

const DNI = syntheticDni();
const PHONE = '11 4444-0187';
const EMAIL = `tutor.${suffix}@example.test`;
const PII = [DNI, PHONE, EMAIL, '4444-0187'];

let browserRef: Browser;
let adminContext: BrowserContext;
let seed: AdminSeed;
let intruder: SeededFamily;
const fam: Record<string, string> = {};
let tutorId = '';
let foreignTutorId = '';

interface StoredFamily {
  nombreReferencia: string;
  activa: boolean;
  version: string;
  tutores: { id: string; nombre: string; apellido: string }[];
}

async function adminPage(): Promise<{ context: BrowserContext; page: Page }> {
  const context = await browserRef.newContext({ baseURL: 'http://127.0.0.1:5173' });
  const page = await context.newPage();
  await loginAdminUi(page);
  return { context, page };
}

/** What the backend really has, read through the admin API. */
async function stored(id: string): Promise<StoredFamily> {
  const response = await adminContext.request.get(`/api/admin/familias/${id}`);
  expect(response.ok()).toBe(true);
  return (await response.json()) as StoredFamily;
}

async function tutorsOf(id: string, nombre: string): Promise<number> {
  return (await stored(id)).tutores.filter((t) => t.nombre === nombre).length;
}

/** «Another administrator»: a write through the API that moves the family revision on. */
async function otherAdminRenames(id: string, version: string, nombreReferencia: string): Promise<void> {
  const response = await adminContext.request.put(`/api/admin/familias/${id}`, {
    data: { nombreReferencia, versionEsperada: version },
    headers: await csrfHeaders(adminContext.request),
  });
  expect(response.status()).toBe(200);
}

async function otherAdminDeactivates(id: string, version: string): Promise<void> {
  const response = await adminContext.request.post(`/api/admin/familias/${id}/desactivar`, {
    data: { versionEsperada: version },
    headers: await csrfHeaders(adminContext.request),
  });
  expect(response.status()).toBe(200);
}

const noHorizontalOverflow = (page: Page) => page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth);
const label = (page: Page, pattern: RegExp) => page.getByLabel(pattern);

async function fillForm(page: Page, values: { nombre: string; apellido: string; dni?: string; telefono?: string; correo?: string; parentesco?: string }): Promise<void> {
  await label(page, /^Nombre/).fill(values.nombre);
  await label(page, /^Apellido/).fill(values.apellido);
  if (values.dni) await label(page, /^DNI/).fill(values.dni);
  if (values.telefono) await label(page, /^Teléfono/).fill(values.telefono);
  if (values.correo) await label(page, /^Correo/).fill(values.correo);
  if (values.parentesco) await label(page, /^Parentesco/).fill(values.parentesco);
}

/** Records the body of every tutor creation the browser sends (to prove which revision it sent). */
function recordCreations(page: Page, familyId: string): string[] {
  const bodies: string[] = [];
  page.on('request', (request) => {
    if (request.method() === 'POST' && new URL(request.url()).pathname === `/api/admin/familias/${familyId}/tutores`) bodies.push(request.postData() ?? '');
  });
  return bodies;
}

function countOf(sql: string, key: string): number | null {
  if (!hasDisposableDb()) return null;
  const match = new RegExp(`${key}=(\\d+)`).exec(dbQuery(sql));
  return match ? Number(match[1]) : null;
}
const accounts = () => countOf("SELECT 'USERS=' || count(*) FROM gestion_patin.usuario;", 'USERS');
const invitations = () => countOf("SELECT 'INV=' || count(*) FROM gestion_patin.invitacion;", 'INV');

test.beforeAll(async ({ browser }) => {
  browserRef = browser;
  adminContext = await browser.newContext({ baseURL: 'http://127.0.0.1:5173' });
  await loginAdminUi(await adminContext.newPage());
  seed = new AdminSeed(adminContext.request);

  for (const key of ['consulta', 'alta', 'doble', 'obsoleta', 'inactivaAlCargar', 'inactivaDespues', 'perdida', 'noLlego', 'ajena', 'movil']) {
    fam[key] = (await seed.createFamily(familyName(key))).id;
  }
  tutorId = (await seed.createTutor(fam.consulta!, { nombre: 'Rosa', apellido: 'Sintetica', telefono: PHONE, email: EMAIL, parentesco: 'Madre' })).id;
  // the DNI of the seeded tutor is random: read it back so the test knows it (it is the real stored value)
  await seed.createTutor(fam.consulta!, { nombre: 'Luis', apellido: 'Sintetico', withDni: false });
  foreignTutorId = (await seed.createTutor(fam.ajena!, { nombre: 'Ajeno', apellido: 'Reservado' })).id;
  await seed.createTutor(fam.inactivaAlCargar!, { nombre: 'Previo', apellido: 'Inactivo' });
  await seed.deactivateFamily(fam.inactivaAlCargar!, '0');

  const anonymous = await browser.newContext({ baseURL: 'http://127.0.0.1:5173' });
  intruder = await seed.createFamilyWithUser(anonymous.request, familyName('usuaria'), {
    nombre: 'Ines',
    apellido: 'Intrusa',
    email: `tutores.${suffix}@example.test`,
    password: `Fam-${randomBytes(8).toString('hex')}`,
  });
  await anonymous.close();
});

test.afterAll(async () => {
  await adminContext.close();
});

test.describe('tutor detail (real backend)', () => {
  test('the family detail keeps the DNI masked and the individual view shows the full data, never in the address, console or storage', async () => {
    // Read the real DNI that the backend stored for the seeded tutor (the seed generated it randomly).
    const individual = await adminContext.request.get(`/api/admin/tutores/${tutorId}`);
    expect(individual.status()).toBe(200);
    const body = (await individual.json()) as { dni: string; version: string; familiaId: string };
    expect(body.dni).toMatch(/^[0-9]{7,9}$/);
    expect(body.familiaId).toBe(fam.consulta);
    const realDni = body.dni;

    const { context, page } = await adminPage();
    const consoleText: string[] = [];
    page.on('console', (message) => consoleText.push(message.text()));
    const familyResponse = page.waitForResponse((r) => r.url().endsWith(`/api/admin/familias/${fam.consulta}`) && r.request().method() === 'GET');
    await page.goto(`/admin/familias/${fam.consulta}`);
    expect(await (await familyResponse).text()).not.toContain(realDni); // the summary never carries the full DNI
    const list = page.getByRole('list', { name: 'Tutores' });
    await expect(list).toContainText(/DNI:\s*[•*]+\d{2,4}/);
    expect((await page.locator('body').innerText()).replace(/\s+/g, ' ')).not.toContain(realDni);

    await list.getByRole('link', { name: 'Rosa Sintetica' }).click();
    await expect(page).toHaveURL(new RegExp(`/admin/familias/${fam.consulta}/tutores/${tutorId}$`));
    await expect(page.getByRole('heading', { level: 1, name: 'Rosa Sintetica' })).toBeVisible();
    await expect(page.getByText(realDni, { exact: true })).toBeVisible(); // the FULL DNI only here
    await expect(page.getByText(PHONE, { exact: true })).toBeVisible();
    await expect(page.getByText(EMAIL, { exact: true })).toBeVisible();
    await expect(page.getByText('Madre', { exact: true })).toBeVisible();
    const text = await page.locator('body').innerText();
    expect(text).not.toMatch(/versi[oó]n|revisi[oó]n/i);
    await expect(page.getByRole('button', { name: /editar|desactivar|eliminar/i })).toHaveCount(0);

    for (const value of [realDni, ...PII]) {
      expect(page.url()).not.toContain(value);
      expect(consoleText.join('\n')).not.toContain(value);
      expect(await page.evaluate(() => JSON.stringify({ ...localStorage, ...sessionStorage }))).not.toContain(value);
    }
    await context.close();
  });

  test('a tutor that does not exist, one of ANOTHER family and a malformed id are the same «not found» screen', async () => {
    const { context, page } = await adminPage();
    const missing = page.waitForResponse((r) => r.url().includes('/api/admin/tutores/') && r.request().method() === 'GET');
    await page.goto(`/admin/familias/${fam.consulta}/tutores/${randomUUID()}`);
    expect((await missing).status()).toBe(404);
    await expect(page.getByRole('heading', { name: 'No encontramos este tutor' })).toBeVisible();

    await page.goto(`/admin/familias/${fam.consulta}/tutores/${foreignTutorId}`); // exists, but belongs to another family
    await expect(page.getByRole('heading', { name: 'No encontramos este tutor' })).toBeVisible();
    expect(await page.locator('body').innerText()).not.toContain('Reservado');
    await expect(page.getByRole('alert')).toHaveCount(0);

    await page.goto(`/admin/familias/${fam.consulta}/tutores/no-es-un-id%20valido`);
    await expect(page.getByRole('heading', { name: 'No encontramos este tutor' })).toBeVisible();
    await page.getByRole('link', { name: 'Volver a la familia' }).click();
    await expect(page.getByRole('heading', { level: 1, name: familyName('consulta') })).toBeVisible();
    await context.close();
  });
});

test.describe('add a tutor (real backend)', () => {
  test('creates a tutor from the family with the captured revision, shows it, creates no account or invitation and leaks nothing', async () => {
    const usersBefore = accounts();
    const invitationsBefore = invitations();
    const before = await stored(fam.alta!);
    const { context, page } = await adminPage();
    const bodies = recordCreations(page, fam.alta!);
    const consoleText: string[] = [];
    page.on('console', (message) => consoleText.push(message.text()));

    await page.goto(`/admin/familias/${fam.alta}`);
    await expect(page.getByText('Esta familia todavía no tiene tutores registrados.')).toBeVisible();
    await page.getByRole('link', { name: 'Agregar tutor' }).click();
    await expect(page).toHaveURL(new RegExp(`/admin/familias/${fam.alta}/tutores/nuevo$`));
    await expect(page.getByText(/Crear un tutor no genera una cuenta de usuario ni una invitación/)).toBeVisible();
    await fillForm(page, { nombre: 'Ana', apellido: 'Pérez', dni: DNI, telefono: PHONE, correo: EMAIL, parentesco: 'Madre' });
    await page.getByRole('button', { name: 'Agregar tutor' }).click();

    await expect(page).toHaveURL(new RegExp(`/admin/familias/${fam.alta}$`));
    await expect(page.getByText('Tutor agregado correctamente.')).toBeVisible();
    const list = page.getByRole('list', { name: 'Tutores' });
    await expect(list.getByRole('link', { name: 'Ana Pérez' })).toBeVisible(); // the visible information was refreshed
    await expect(list).toContainText(/DNI:\s*[•*]+\d{2,4}/);
    expect((await page.locator('body').innerText()).replace(/\s+/g, ' ')).not.toContain(DNI);

    expect(bodies).toHaveLength(1);
    expect(JSON.parse(bodies[0]!)).toMatchObject({ nombre: 'Ana', apellido: 'Pérez', versionFamiliaEsperada: before.version });
    const after = await stored(fam.alta!);
    expect(after.tutores.filter((t) => t.nombre === 'Ana')).toHaveLength(1);
    expect(after.version).toBe(before.version); // creating a tutor does not move the family revision
    if (usersBefore !== null) expect(accounts()).toBe(usersBefore);
    if (invitationsBefore !== null) expect(invitations()).toBe(invitationsBefore);

    for (const value of PII) {
      expect(page.url()).not.toContain(value);
      expect(consoleText.join('\n')).not.toContain(value);
      expect(await page.evaluate(() => JSON.stringify({ ...localStorage, ...sessionStorage }))).not.toContain(value);
    }
    await context.close();
  });

  test('validation is explained in the form and nothing is sent', async () => {
    const { context, page } = await adminPage();
    const bodies = recordCreations(page, fam.alta!);
    await page.goto(`/admin/familias/${fam.alta}/tutores/nuevo`);
    await page.getByRole('button', { name: 'Agregar tutor' }).click();
    await expect(page.getByText('Ingresá el nombre.')).toBeVisible();
    await expect(page.getByText('Ingresá el apellido.')).toBeVisible();
    await fillForm(page, { nombre: 'Ana', apellido: 'Pérez', dni: '12345', correo: 'no-es-un-correo' });
    await page.getByRole('button', { name: 'Agregar tutor' }).click();
    await expect(page.getByText('El DNI debe tener entre 7 y 9 dígitos.')).toBeVisible();
    await expect(page.getByText('Ingresá un correo electrónico válido.')).toBeVisible();
    expect(bodies).toHaveLength(0);
    await context.close();
  });

  test('a double click on «Agregar tutor» creates a single tutor', async () => {
    const { context, page } = await adminPage();
    await page.goto(`/admin/familias/${fam.doble}/tutores/nuevo`);
    await fillForm(page, { nombre: 'Doble', apellido: 'Clic' });
    await page.getByRole('button', { name: 'Agregar tutor' }).dblclick();
    await expect(page.getByText('Tutor agregado correctamente.')).toBeVisible();
    expect(await tutorsOf(fam.doble!, 'Doble')).toBe(1);
    await context.close();
  });

  test('a stale family revision is rejected by the real backend: the draft survives, nothing is created and only an explicit choice continues', async () => {
    const { context, page } = await adminPage();
    const bodies = recordCreations(page, fam.obsoleta!);
    await page.goto(`/admin/familias/${fam.obsoleta}/tutores/nuevo`);
    await fillForm(page, { nombre: 'Borrador', apellido: 'Guardado', telefono: PHONE });
    await otherAdminRenames(fam.obsoleta!, '0', familyName('obsoleta renombrada')); // the revision moves on behind the browser's back

    await page.getByRole('button', { name: 'Agregar tutor' }).click();
    await expect(page.getByRole('alert')).toContainText('La familia cambió mientras completabas el formulario');
    await expect(label(page, /^Nombre/)).toHaveValue('Borrador');
    await expect(label(page, /^Teléfono/)).toHaveValue(PHONE);
    await expect(page.getByRole('button', { name: 'Agregar tutor' })).toBeDisabled();
    expect(await tutorsOf(fam.obsoleta!, 'Borrador')).toBe(0);
    await page.waitForTimeout(1_000);
    expect(bodies).toHaveLength(1); // no automatic retry

    await page.getByRole('button', { name: 'Ver datos vigentes' }).click();
    const panel = page.getByRole('group', { name: 'Datos vigentes' });
    await expect(panel).toContainText(familyName('obsoleta renombrada'));
    expect(bodies).toHaveLength(1); // looking is not writing
    await page.getByRole('button', { name: 'Seguir con mi borrador' }).click();
    await page.getByRole('button', { name: 'Agregar tutor' }).click();
    await expect(page.getByText('Tutor agregado correctamente.')).toBeVisible();
    expect(bodies).toHaveLength(2);
    expect(JSON.parse(bodies[0]!)).toMatchObject({ versionFamiliaEsperada: '0' });
    expect(JSON.parse(bodies[1]!)).toMatchObject({ versionFamiliaEsperada: '1' }); // only after the explicit choice
    expect(await tutorsOf(fam.obsoleta!, 'Borrador')).toBe(1);
    await context.close();
  });

  test('an inactive family is explained and gets no form; a family deactivated meanwhile gets its own message and nothing is created', async () => {
    const { context, page } = await adminPage();
    await page.goto(`/admin/familias/${fam.inactivaAlCargar}`);
    await expect(page.getByRole('button', { name: 'Agregar tutor' })).toBeDisabled();
    await expect(page.getByRole('link', { name: 'Agregar tutor' })).toHaveCount(0);
    await page.goto(`/admin/familias/${fam.inactivaAlCargar}/tutores/nuevo`);
    await expect(page.getByText(/La familia está inactiva: no se pueden agregar tutores/)).toBeVisible();
    await expect(label(page, /^Nombre/)).toHaveCount(0);

    const bodies = recordCreations(page, fam.inactivaDespues!);
    await page.goto(`/admin/familias/${fam.inactivaDespues}/tutores/nuevo`);
    await fillForm(page, { nombre: 'Sin', apellido: 'Destino' });
    await otherAdminDeactivates(fam.inactivaDespues!, '0');
    await page.getByRole('button', { name: 'Agregar tutor' }).click();
    const alert = page.getByRole('alert');
    await expect(alert).toContainText('La familia está inactiva: no se pueden agregar tutores');
    await expect(alert).not.toContainText('La familia cambió');
    await expect(label(page, /^Nombre/)).toHaveValue('Sin');
    await expect(page.getByRole('button', { name: 'Agregar tutor' })).toBeDisabled();
    expect(bodies).toHaveLength(1);
    expect(await tutorsOf(fam.inactivaDespues!, 'Sin')).toBe(0);
    await context.close();
  });

  test('simulated outage, answer lost AFTER the backend created the tutor: no retry, the verification never claims it created it and nothing is duplicated', async () => {
    const { context, page } = await adminPage();
    let attempts = 0;
    await page.route(`**/api/admin/familias/${fam.perdida}/tutores`, async (route) => {
      if (route.request().method() !== 'POST') return route.continue();
      attempts += 1;
      await route.fetch(); // the request DOES reach the backend, which creates the tutor...
      return route.abort('failed'); // ...but the browser never sees the answer
    });
    await page.goto(`/admin/familias/${fam.perdida}/tutores/nuevo`);
    await fillForm(page, { nombre: 'Perdida', apellido: 'Respuesta' });
    await page.getByRole('button', { name: 'Agregar tutor' }).click();
    await expect(page.getByRole('alert')).toContainText('No pudimos confirmar si el tutor se creó');
    await page.waitForTimeout(1_000);
    expect(attempts).toBe(1); // never retried by itself
    await expect(label(page, /^Nombre/)).toHaveValue('Perdida');
    await expect(page.getByRole('button', { name: 'Agregar tutor' })).toBeDisabled(); // a blind resend would create it twice
    expect(await tutorsOf(fam.perdida!, 'Perdida')).toBe(1); // it WAS created

    await page.getByRole('button', { name: 'Verificar si se creó' }).click();
    const panel = page.getByRole('group', { name: 'Verificación' });
    // Without idempotency a same-name tutor is only a hint: it is never presented as proof that OUR request created it.
    await expect(panel).toContainText('no podemos confirmarlo');
    await expect(panel).not.toContainText('ya figura creado');
    await expect(panel.getByRole('button', { name: 'Volver a intentar' })).toHaveCount(0);
    await expect(page.getByRole('button', { name: 'Agregar tutor' })).toBeDisabled();
    expect(attempts).toBe(1);
    expect(await tutorsOf(fam.perdida!, 'Perdida')).toBe(1);

    await panel.getByRole('link', { name: 'Ver la familia' }).click();
    await page.getByRole('button', { name: 'Descartar y salir' }).click(); // the unconfirmed draft is still guarded
    await expect(page.getByRole('list', { name: 'Tutores' })).toBeVisible();
    await expect(page.getByText('Tutor agregado correctamente.')).toHaveCount(0); // only a server-confirmed creation shows it
    expect(attempts).toBe(1);
    expect(await tutorsOf(fam.perdida!, 'Perdida')).toBe(1);
    await context.close();
  });

  test('simulated outage, request that never arrived: the verification only reports that nothing was found at that moment and an explicit action allows trying again, once', async () => {
    const { context, page } = await adminPage();
    let attempts = 0;
    await page.route(`**/api/admin/familias/${fam.noLlego}/tutores`, async (route) => {
      if (route.request().method() !== 'POST') return route.continue();
      attempts += 1;
      return route.abort('failed'); // it never reaches the backend
    });
    await page.goto(`/admin/familias/${fam.noLlego}/tutores/nuevo`);
    await fillForm(page, { nombre: 'Nunca', apellido: 'Llego' });
    await page.getByRole('button', { name: 'Agregar tutor' }).click();
    await expect(page.getByRole('alert')).toContainText('No pudimos confirmar si el tutor se creó');
    expect(await tutorsOf(fam.noLlego!, 'Nunca')).toBe(0);

    await page.getByRole('button', { name: 'Verificar si se creó' }).click();
    const panel = page.getByRole('group', { name: 'Verificación' });
    await expect(panel).toContainText('No encontramos el tutor al verificar. La solicitud original podría seguir procesándose');
    await expect(panel).toContainText('el tutor podría quedar duplicado');
    await expect(page.getByRole('button', { name: 'Agregar tutor' })).toBeDisabled(); // still blocked until the explicit action
    expect(attempts).toBe(1); // verifying never retries
    await page.unroute(`**/api/admin/familias/${fam.noLlego}/tutores`); // the network is back
    await page.getByRole('button', { name: 'Volver a intentar' }).click();
    await page.getByRole('button', { name: 'Agregar tutor' }).click();
    await expect(page.getByText('Tutor agregado correctamente.')).toBeVisible();
    expect(attempts).toBe(1);
    expect(await tutorsOf(fam.noLlego!, 'Nunca')).toBe(1);
    await context.close();
  });
});

test.describe('permissions and layout (real backend)', () => {
  test('anonymous, family, CSRF-less and screen access are rejected and create or reveal nothing', async ({ browser, request }) => {
    expect((await request.get(`/api/admin/tutores/${tutorId}`)).status()).toBe(401);
    const body = { nombre: 'Intruso', apellido: 'Anonimo', versionFamiliaEsperada: '0' };
    expect((await request.post(`/api/admin/familias/${fam.ajena}/tutores`, { data: body, headers: await csrfHeaders(request) })).status()).toBe(401);

    const familyContext = await browser.newContext({ baseURL: 'http://127.0.0.1:5173' });
    const page = await familyContext.newPage();
    await page.goto('/login');
    await page.getByLabel(/^Correo electrónico/).fill(intruder.email);
    await page.getByLabel(/^Contraseña/).fill(intruder.password);
    await page.getByRole('button', { name: 'Ingresar' }).click();
    await expect(page).toHaveURL(/\/familia$/);
    expect((await familyContext.request.get(`/api/admin/tutores/${tutorId}`)).status()).toBe(403);
    const familyHeaders = await csrfHeaders(familyContext.request);
    expect((await familyContext.request.post(`/api/admin/familias/${fam.ajena}/tutores`, { data: { ...body, nombre: 'Intrusa' }, headers: familyHeaders })).status()).toBe(403);
    for (const path of [`/admin/familias/${fam.consulta}/tutores/${tutorId}`, `/admin/familias/${fam.ajena}/tutores/nuevo`]) {
      await page.goto(path);
      await expect(page.getByRole('heading', { name: 'No tenés permiso para ver esta sección' })).toBeVisible();
    }
    expect(await page.locator('body').innerText()).not.toContain('Rosa');
    await familyContext.close();

    // The administrator without the CSRF header.
    expect((await adminContext.request.post(`/api/admin/familias/${fam.ajena}/tutores`, { data: { ...body, nombre: 'SinCsrf' } })).status()).toBe(403);
    const after = await stored(fam.ajena!);
    expect(after.tutores.map((t) => t.nombre)).toEqual(['Ajeno']);
  });

  test('the detail and the form fit a 360 px phone without horizontal scrolling', async () => {
    const { context, page } = await adminPage();
    await page.setViewportSize({ width: 360, height: 740 });
    await page.goto(`/admin/familias/${fam.consulta}`);
    await expect(page.getByRole('list', { name: 'Tutores' })).toBeVisible();
    expect(await noHorizontalOverflow(page)).toBe(true);
    await page.getByRole('list', { name: 'Tutores' }).getByRole('link', { name: 'Rosa Sintetica' }).click();
    await expect(page.getByRole('heading', { level: 1, name: 'Rosa Sintetica' })).toBeVisible();
    expect(await noHorizontalOverflow(page)).toBe(true);

    await page.goto(`/admin/familias/${fam.movil}/tutores/nuevo`);
    await expect(page.getByRole('heading', { name: 'Nuevo tutor' })).toBeVisible();
    await fillForm(page, { nombre: 'Nombre bastante largo para forzar el ajuste de línea', apellido: 'Apellido igualmente extenso para pantallas chicas', correo: `un.correo.muy.largo.${suffix}@example.test` });
    expect(await noHorizontalOverflow(page)).toBe(true);
    for (const control of [label(page, /^Nombre/), label(page, /^DNI/), page.getByRole('button', { name: 'Agregar tutor' })]) {
      expect((await control.boundingBox())?.height ?? 0).toBeGreaterThanOrEqual(44);
    }
    await context.close();
  });
});
