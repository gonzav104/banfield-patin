import { randomBytes, randomUUID } from 'node:crypto';
import { expect, test, type Browser, type BrowserContext, type Page } from '@playwright/test';
import { AdminSeed, csrfHeaders, loginAdminUi, type SeededFamily } from './support/seed';

/**
 * REAL flows of the administrative family detail and creation (FE-05 / U2, PR B): browser -> Vite proxy -> Spring backend
 * -> PostgreSQL. Requires the administrator enrolled by auth.real.spec.ts (secret in e2e/.state.json). Everything is
 * synthetic and created through the real admin API. School isolation between two schools is covered by the backend DB
 * tests; here the evidence is that a family the school does not have is the same «not found» as one that never existed.
 *
 * Only the test labelled "simulated outage" uses page.route. No traces, screenshots or videos are recorded (see playwright.config.ts):
 * names, e-mails and DNIs never reach artifacts.
 */
test.describe.configure({ mode: 'serial' });

const suffix = randomBytes(3).toString('hex');
const CREATED_NAME = `E2E Alta ${suffix}`;
const DOUBLE_NAME = `E2E Doble ${suffix}`;
const DETAIL_NAME = `E2E Detalle ${suffix}`;
const EMPTY_NAME = `E2E Vacia ${suffix}`;
const TUTOR_EMAIL = `tutor.${suffix}@example.test`;

let browserRef: Browser;
let adminContext: BrowserContext;
let detailFamilyId = '';
let emptyFamilyId = '';
let tutorDni = '';
let familyUser: SeededFamily;

async function adminPage(): Promise<{ context: BrowserContext; page: Page }> {
  const context = await browserRef.newContext({ baseURL: 'http://127.0.0.1:5173' });
  const page = await context.newPage();
  await loginAdminUi(page);
  return { context, page };
}

const noHorizontalOverflow = (page: Page) => page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth);

async function countByName(name: string): Promise<number> {
  const response = await adminContext.request.get(`/api/admin/familias/listado?busqueda=${encodeURIComponent(name)}&size=50`);
  expect(response.ok()).toBe(true);
  return ((await response.json()) as { totalElementos: number }).totalElementos;
}

test.beforeAll(async ({ browser }) => {
  browserRef = browser;
  adminContext = await browser.newContext({ baseURL: 'http://127.0.0.1:5173' });
  await loginAdminUi(await adminContext.newPage());
  const seed = new AdminSeed(adminContext.request);

  detailFamilyId = (await seed.createFamily(DETAIL_NAME)).id;
  const withDni = await seed.createTutor(detailFamilyId, { nombre: 'Rosa', apellido: 'Sintetica', telefono: '11 5555-0101', email: TUTOR_EMAIL, parentesco: 'Madre' });
  tutorDni = withDni.dni ?? '';
  await seed.createTutor(detailFamilyId, { nombre: 'Luis', apellido: 'Sintetico', withDni: false });
  emptyFamilyId = (await seed.createFamily(EMPTY_NAME)).id;

  const anonymous = await browser.newContext({ baseURL: 'http://127.0.0.1:5173' });
  familyUser = await seed.createFamilyWithUser(anonymous.request, `E2E Alta Usuaria ${suffix}`, {
    nombre: 'Rita',
    apellido: 'Prueba',
    email: `alta.${suffix}@example.test`,
    password: `Fam-${randomBytes(8).toString('hex')}`,
  });
  await anonymous.close();
});

test.afterAll(async () => {
  await adminContext.close();
});

test.describe('administrative family detail (real backend)', () => {
  test('shows the family and its tutors with the DNI masked, and never the revision', async () => {
    const { context, page } = await adminPage();
    const detail = page.waitForResponse((r) => r.url().endsWith(`/api/admin/familias/${detailFamilyId}`) && r.request().method() === 'GET');
    await page.goto(`/admin/familias/${detailFamilyId}`);
    const response = await detail;
    expect(response.status()).toBe(200);
    expect(await response.text()).not.toContain(tutorDni); // the API itself already masks it

    await expect(page.getByRole('heading', { level: 1, name: DETAIL_NAME })).toBeVisible();
    await expect(page.getByText('Activa', { exact: true })).toBeVisible();
    const tutors = page.getByRole('list', { name: 'Tutores' }).getByRole('listitem');
    await expect(tutors).toHaveCount(2);
    await expect(tutors.filter({ hasText: 'Rosa Sintetica' })).toContainText('Madre');
    await expect(tutors.filter({ hasText: 'Rosa Sintetica' })).toContainText(TUTOR_EMAIL);
    await expect(tutors.filter({ hasText: 'Rosa Sintetica' })).toContainText(/DNI:\s*[•*]+\d{2,4}/);
    await expect(tutors.filter({ hasText: 'Luis Sintetico' })).toContainText(/DNI:\s*No informado/);

    const text = (await page.locator('body').innerText()).replace(/\s+/g, ' ');
    expect(text).not.toContain(tutorDni);
    expect(text).not.toMatch(/versi[oó]n|revisi[oó]n/i);
    expect(page.url()).not.toContain(DETAIL_NAME);
    await context.close();
  });

  test('a family without tutors explains it and points to invitations; the list links to the detail', async () => {
    const { context, page } = await adminPage();
    await page.goto('/admin/familias');
    await page.getByRole('searchbox', { name: 'Buscar por nombre' }).fill(EMPTY_NAME);
    await page.getByRole('link', { name: EMPTY_NAME }).click();
    await expect(page).toHaveURL(new RegExp(`/admin/familias/${emptyFamilyId}$`));
    await expect(page.getByText('Esta familia todavía no tiene tutores registrados.')).toBeVisible();
    await expect(page.getByText(/El acceso de las familias se gestiona con invitaciones/)).toBeVisible();
    await page.getByRole('link', { name: 'Volver al listado' }).click();
    await expect(page).toHaveURL(/\/admin\/familias$/);
    await context.close();
  });

  test('a family that does not exist and a malformed id are the same «not found» screen', async () => {
    const { context, page } = await adminPage();
    const missing = page.waitForResponse((r) => r.url().includes('/api/admin/familias/') && r.request().method() === 'GET');
    await page.goto(`/admin/familias/${randomUUID()}`);
    expect((await missing).status()).toBe(404);
    await expect(page.getByRole('heading', { name: 'No encontramos esta familia' })).toBeVisible();
    await expect(page.getByRole('alert')).toHaveCount(0);

    await page.goto('/admin/familias/no-es-un-uuid');
    await expect(page.getByRole('heading', { name: 'No encontramos esta familia' })).toBeVisible();
    await page.getByRole('link', { name: 'Volver al listado' }).click();
    await expect(page.getByRole('heading', { level: 1, name: 'Familias' })).toBeVisible();
    await context.close();
  });
});

test.describe('administrative family creation (real backend)', () => {
  test('creates a family from the list, lands on its detail and the family is stored once', async () => {
    const { context, page } = await adminPage();
    const consoleText: string[] = [];
    page.on('console', (message) => consoleText.push(message.text()));
    await page.goto('/admin/familias');
    await page.getByRole('link', { name: 'Nueva familia' }).click();
    await expect(page).toHaveURL(/\/admin\/familias\/nueva$/);
    await expect(page.getByText(/Crear una familia no genera una cuenta de usuario/)).toBeVisible();
    await expect(page.getByText(/enviá una invitación/)).toBeVisible();

    await page.getByLabel(/Nombre de referencia/).fill(`  ${CREATED_NAME}  `);
    await page.getByRole('button', { name: 'Crear familia' }).click();

    await expect(page).toHaveURL(/\/admin\/familias\/[0-9a-f-]{36}$/);
    await expect(page.getByRole('heading', { level: 1, name: CREATED_NAME })).toBeVisible();
    await expect(page.getByText(/La familia se creó correctamente/)).toBeVisible();
    await expect(page.getByText('Esta familia todavía no tiene tutores registrados.')).toBeVisible();
    expect(await countByName(CREATED_NAME)).toBe(1);

    expect(page.url()).not.toContain(suffix);
    expect(consoleText.join('\n')).not.toContain(suffix);
    expect(await page.evaluate(() => JSON.stringify({ ...localStorage, ...sessionStorage }))).not.toContain(suffix);

    await page.getByRole('link', { name: 'Volver al listado' }).click();
    await page.getByRole('searchbox', { name: 'Buscar por nombre' }).fill(CREATED_NAME);
    await expect(page.getByRole('link', { name: CREATED_NAME })).toBeVisible();
    await context.close();
  });

  test('an empty name is explained in the form and nothing is sent', async () => {
    const { context, page } = await adminPage();
    const posts: string[] = [];
    page.on('request', (request) => {
      if (request.method() === 'POST' && new URL(request.url()).pathname === '/api/admin/familias') posts.push(request.url());
    });
    await page.goto('/admin/familias/nueva');
    await page.getByRole('button', { name: 'Crear familia' }).click();
    await expect(page.getByText('Ingresá un nombre de referencia.')).toBeVisible();
    await page.getByLabel(/Nombre de referencia/).fill('   ');
    await page.getByRole('button', { name: 'Crear familia' }).click();
    await expect(page.getByText('Ingresá un nombre de referencia.')).toBeVisible();
    expect(posts).toHaveLength(0);
    await expect(page.getByLabel(/Nombre de referencia/)).toHaveAttribute('maxlength', '150');
    await context.close();
  });

  test('a double click creates a single family', async () => {
    const { context, page } = await adminPage();
    await page.goto('/admin/familias/nueva');
    await page.getByLabel(/Nombre de referencia/).fill(DOUBLE_NAME);
    await page.getByRole('button', { name: 'Crear familia' }).dblclick();
    await expect(page.getByRole('heading', { level: 1, name: DOUBLE_NAME })).toBeVisible();
    expect(await countByName(DOUBLE_NAME)).toBe(1);
    await context.close();
  });

  test('simulated outage: a network failure does not claim the family was not created, does not retry and keeps the data', async () => {
    const { context, page } = await adminPage();
    let attempts = 0;
    await page.route('**/api/admin/familias', async (route) => {
      if (route.request().method() === 'POST') {
        attempts += 1;
        await route.abort('failed'); // simulated outage: the request never reaches the backend
      } else {
        await route.continue();
      }
    });
    await page.goto('/admin/familias/nueva');
    const name = `E2E Incierta ${suffix}`;
    await page.getByLabel(/Nombre de referencia/).fill(name);
    await page.getByRole('button', { name: 'Crear familia' }).click();
    await expect(page.getByRole('alert')).toContainText('No pudimos confirmar si la familia se creó');
    await expect(page.getByRole('alert').getByRole('link', { name: 'Revisar el listado' })).toBeVisible();
    await page.waitForTimeout(1_000);
    expect(attempts).toBe(1);
    await expect(page.getByLabel(/Nombre de referencia/)).toHaveValue(name);
    await expect(page.getByRole('button', { name: 'Crear familia' })).toBeEnabled();
    expect(await countByName(name)).toBe(0);
    await context.close();
  });

  test('works on a 360 px phone without horizontal scrolling', async () => {
    const { context, page } = await adminPage();
    await page.setViewportSize({ width: 360, height: 740 });
    await page.goto('/admin/familias/nueva');
    await expect(page.getByRole('heading', { name: 'Nueva familia' })).toBeVisible();
    expect(await noHorizontalOverflow(page)).toBe(true);
    for (const control of [page.getByLabel(/Nombre de referencia/), page.getByRole('button', { name: 'Crear familia' })]) {
      expect((await control.boundingBox())?.height ?? 0).toBeGreaterThanOrEqual(44);
    }
    await page.goto(`/admin/familias/${detailFamilyId}`);
    await expect(page.getByRole('heading', { level: 1, name: DETAIL_NAME })).toBeVisible();
    expect(await noHorizontalOverflow(page)).toBe(true);
    await context.close();
  });
});

test.describe('permissions (real backend)', () => {
  test('anonymous requests get 401 on the detail and on the creation', async ({ request }) => {
    expect((await request.get(`/api/admin/familias/${detailFamilyId}`)).status()).toBe(401);
    const headers = await csrfHeaders(request);
    expect((await request.post('/api/admin/familias', { data: { nombreReferencia: `E2E Anonima ${suffix}` }, headers })).status()).toBe(401);
    expect(await countByName(`E2E Anonima ${suffix}`)).toBe(0);
  });

  test('a family account gets 403 from the APIs and no admin screen, and creates nothing', async ({ browser }) => {
    const context = await browser.newContext({ baseURL: 'http://127.0.0.1:5173' });
    const page = await context.newPage();
    await page.goto('/login');
    await page.getByLabel(/^Correo electrónico/).fill(familyUser.email);
    await page.getByLabel(/^Contraseña/).fill(familyUser.password);
    await page.getByRole('button', { name: 'Ingresar' }).click();
    await expect(page).toHaveURL(/\/familia$/);

    expect((await context.request.get(`/api/admin/familias/${detailFamilyId}`)).status()).toBe(403);
    const headers = await csrfHeaders(context.request);
    expect((await context.request.post('/api/admin/familias', { data: { nombreReferencia: `E2E Intrusa ${suffix}` }, headers })).status()).toBe(403);
    expect(await countByName(`E2E Intrusa ${suffix}`)).toBe(0);

    for (const path of [`/admin/familias/${detailFamilyId}`, '/admin/familias/nueva']) {
      await page.goto(path);
      await expect(page.getByRole('heading', { name: 'No tenés permiso para ver esta sección' })).toBeVisible();
    }
    await context.close();
  });
});
