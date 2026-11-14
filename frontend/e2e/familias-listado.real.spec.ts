import { randomBytes } from 'node:crypto';
import { expect, test, type Browser, type BrowserContext, type Page } from '@playwright/test';
import { AdminSeed, loginAdminUi, type SeededFamily } from './support/seed';

/**
 * REAL flows of the administrative family list (FE-05 / U2, PR A): browser -> Vite proxy -> Spring backend -> PostgreSQL.
 * Requires the administrator enrolled by auth.real.spec.ts (secret in e2e/.state.json). Everything is synthetic and is
 * created through the real admin API: 25 families sharing a unique search term (so the list has two real pages of 20 and
 * 5), one of them inactive and one with a linked, active athlete. School isolation between two schools is covered by the
 * backend DB tests; here the evidence is that the browser only ever sees the rows the API returns for this school.
 *
 * The search term is a synthetic name. No traces, screenshots or videos are recorded (see playwright.config.ts).
 */
test.describe.configure({ mode: 'serial' });

const suffix = randomBytes(3).toString('hex');
const TERM = `E2E-Listado-${suffix}`;
const NAME = (n: number) => `${TERM} ${String(n).padStart(2, '0')}`;
const TOTAL = 25;
const INACTIVE = 25;
const WITH_ATHLETE = 1;

let browserRef: Browser;
let adminContext: BrowserContext;
let familyUser: SeededFamily;

async function adminPage(): Promise<{ context: BrowserContext; page: Page }> {
  const context = await browserRef.newContext({ baseURL: 'http://127.0.0.1:5173' });
  const page = await context.newPage();
  await loginAdminUi(page);
  return { context, page };
}

const rows = (page: Page) => page.getByRole('main').getByRole('listitem');

test.beforeAll(async ({ browser }) => {
  browserRef = browser;
  adminContext = await browser.newContext({ baseURL: 'http://127.0.0.1:5173' });
  await loginAdminUi(await adminContext.newPage());
  const seed = new AdminSeed(adminContext.request);

  const ids: string[] = [];
  for (let n = 1; n <= TOTAL; n += 1) ids.push((await seed.createFamily(NAME(n))).id);
  await seed.deactivateFamily(ids[INACTIVE - 1]!, '0');
  const athlete = await seed.createAthlete({ nombre: `E2E-Listado-${suffix}`, apellido: 'Sintetico' });
  await seed.link(ids[WITH_ATHLETE - 1]!, [athlete.id]);

  const anonymous = await browser.newContext({ baseURL: 'http://127.0.0.1:5173' });
  familyUser = await seed.createFamilyWithUser(anonymous.request, `E2E Listado Usuaria ${suffix}`, {
    nombre: 'Rita',
    apellido: 'Prueba',
    email: `listado.${suffix}@example.test`,
    password: `Fam-${randomBytes(8).toString('hex')}`,
  });
  await anonymous.close();
});

test.afterAll(async () => {
  await adminContext.close();
});

test.describe('administrative family list (real backend)', () => {
  test('is reached from the navigation and shows real families with the real total', async () => {
    const { context, page } = await adminPage();
    await page.getByRole('navigation', { name: 'Principal' }).getByRole('link', { name: 'Familias' }).click();
    await expect(page).toHaveURL(/\/admin\/familias$/);
    await expect(page.getByRole('heading', { name: 'Familias', level: 1 })).toBeVisible();
    await expect(rows(page).first()).toBeVisible();
    await context.close();
  });

  test('searches by name, paginates two real pages and keeps the term out of the address, console and storage', async () => {
    const { context, page } = await adminPage();
    const consoleText: string[] = [];
    page.on('console', (message) => consoleText.push(message.text()));
    await page.goto('/admin/familias');
    await page.getByRole('searchbox', { name: 'Buscar por nombre' }).fill(TERM);

    await expect(page.getByText(`${TOTAL} familias`)).toBeVisible();
    await expect(rows(page)).toHaveCount(20);
    await expect(rows(page).first()).toContainText(NAME(1));
    await expect(page.getByText(/Página 1 de 2/)).toBeVisible();

    await page.getByRole('button', { name: 'Siguiente' }).click();
    await expect(rows(page)).toHaveCount(5);
    await expect(rows(page).last()).toContainText(NAME(TOTAL));
    await expect(page).toHaveURL(/\/admin\/familias\?pagina=2$/);

    expect(page.url()).not.toContain(suffix);
    expect(consoleText.join('\n')).not.toContain(suffix);
    const stored = await page.evaluate(() => JSON.stringify({ ...localStorage, ...sessionStorage }));
    expect(stored).not.toContain(suffix);
    await context.close();
  });

  test('filters by status against the backend', async () => {
    const { context, page } = await adminPage();
    await page.goto('/admin/familias');
    await page.getByRole('searchbox', { name: 'Buscar por nombre' }).fill(TERM);
    await expect(page.getByText(`${TOTAL} familias`)).toBeVisible();

    await page.getByLabel('Estado').selectOption('INACTIVOS');
    await expect(page).toHaveURL(/estado=INACTIVOS$/);
    await expect(rows(page)).toHaveCount(1);
    await expect(rows(page).first()).toContainText(NAME(INACTIVE));
    await expect(rows(page).first().getByText('Inactiva')).toBeVisible();

    await page.getByLabel('Estado').selectOption('ACTIVOS');
    await expect(page.getByText(`${TOTAL - 1} familias`)).toBeVisible();
    await expect(page.getByText('Inactiva', { exact: true })).toHaveCount(0);
    await context.close();
  });

  test('shows the real counts of tutors and active athletes', async () => {
    const { context, page } = await adminPage();
    await page.goto('/admin/familias');
    await page.getByRole('searchbox', { name: 'Buscar por nombre' }).fill(NAME(WITH_ATHLETE));
    await expect(rows(page)).toHaveCount(1);
    await expect(rows(page).first()).toContainText(/Tutores:\s*0/);
    await expect(rows(page).first()).toContainText(/Deportistas activos:\s*1/);
    await context.close();
  });

  test('explains an empty result and clears every filter', async () => {
    const { context, page } = await adminPage();
    await page.goto('/admin/familias?estado=INACTIVOS');
    await page.getByRole('searchbox', { name: 'Buscar por nombre' }).fill(`${TERM}-inexistente`);
    await expect(page.getByText('No encontramos familias con esos criterios.')).toBeVisible();
    await page.getByRole('button', { name: 'Limpiar filtros' }).click();
    await expect(page).toHaveURL(/\/admin\/familias$/);
    await expect(page.getByRole('searchbox', { name: 'Buscar por nombre' })).toHaveValue('');
    await expect(rows(page).first()).toBeVisible();
    await context.close();
  });

  test('a page beyond the end is explained, not blank', async () => {
    const { context, page } = await adminPage();
    await page.goto('/admin/familias?pagina=99999');
    await expect(page.getByText('Esa página ya no existe.')).toBeVisible();
    await page.getByRole('button', { name: 'Volver a la primera página' }).click();
    await expect(rows(page).first()).toBeVisible();
    await context.close();
  });

  test('works on a 360 px phone without horizontal scrolling', async () => {
    const { context, page } = await adminPage();
    await page.setViewportSize({ width: 360, height: 740 });
    await page.goto('/admin/familias');
    await page.getByRole('searchbox', { name: 'Buscar por nombre' }).fill(TERM);
    await expect(rows(page).first()).toBeVisible();
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
    for (const control of [page.getByRole('searchbox', { name: 'Buscar por nombre' }), page.getByLabel('Estado')]) {
      const box = await control.boundingBox();
      expect(box?.height ?? 0).toBeGreaterThanOrEqual(44);
    }
    await context.close();
  });

  test('anonymous requests get 401 and a family account gets 403 from the list API and no screen', async ({ request, browser }) => {
    expect((await request.get('/api/admin/familias/listado')).status()).toBe(401);

    const context = await browser.newContext({ baseURL: 'http://127.0.0.1:5173' });
    const page = await context.newPage();
    await page.goto('/login');
    await page.getByLabel(/^Correo electrónico/).fill(familyUser.email);
    await page.getByLabel(/^Contraseña/).fill(familyUser.password);
    await page.getByRole('button', { name: 'Ingresar' }).click();
    await expect(page).toHaveURL(/\/familia$/);

    expect((await context.request.get('/api/admin/familias/listado')).status()).toBe(403);
    await page.goto('/admin/familias');
    await expect(page.getByRole('heading', { name: 'No tenés permiso para ver esta sección' })).toBeVisible();
    await expect(page.getByRole('heading', { name: 'Familias', level: 1 })).toHaveCount(0);
    await context.close();
  });
});
