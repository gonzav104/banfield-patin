import { randomBytes, randomUUID } from 'node:crypto';
import { mkdirSync } from 'node:fs';
import { expect, test, type Browser, type BrowserContext, type Page } from '@playwright/test';
import { AdminSeed, loginAdminUi, type SeededFamily } from './support/seed';

/**
 * REAL flows of the family portal (FE-03, first unit): browser -> Vite proxy -> Spring backend -> PostgreSQL.
 * Requires the administrator enrolled by auth.real.spec.ts (secret in e2e/.state.json). All data is synthetic and is
 * created through the real admin API: family A (12 visible athletes over two pages, one inactive, one without birth
 * date, one with a revoked link) and family B (no athletes). Only the test labelled "simulated" uses page.route.
 */
test.describe.configure({ mode: 'serial' });

const ARTIFACTS = 'e2e/artifacts';
mkdirSync(ARTIFACTS, { recursive: true });

const suffix = randomBytes(3).toString('hex');
const password = () => `Fam-${randomBytes(8).toString('hex')}`;
const NAME = (n: number) => `E2E-Atleta-${String(n).padStart(2, '0')}`;
const CARD = /E2E-Atleta-\d\d Sintetico/; // the card also holds the avatar initials, so no ^ anchor
const INACTIVE = 5;
const WITHOUT_BIRTH_DATE = 12;
const REVOKED = 13;

let browserRef: Browser;
let adminContext: BrowserContext;
let familyA: SeededFamily;
let familyB: SeededFamily;
const athleteIds: string[] = [];
const dnis: string[] = [];

async function newContext(): Promise<{ context: BrowserContext; page: Page }> {
  const context = await browserRef.newContext({ baseURL: 'http://127.0.0.1:5173' });
  return { context, page: await context.newPage() };
}

async function loginFamilyUi(page: Page, family: SeededFamily): Promise<void> {
  await page.goto('/login');
  await page.getByLabel(/^Correo electrónico/).fill(family.email);
  await page.getByLabel(/^Contraseña/).fill(family.password);
  await page.getByRole('button', { name: 'Ingresar' }).click();
  await expect(page).toHaveURL(/\/familia$/);
}

const athleteCards = (page: Page) => page.getByRole('listitem').filter({ hasText: CARD });
const noHorizontalOverflow = (page: Page) => page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth);

test.beforeAll(async ({ browser }) => {
  browserRef = browser;
  adminContext = await browser.newContext({ baseURL: 'http://127.0.0.1:5173' });
  await loginAdminUi(await adminContext.newPage());
  const seed = new AdminSeed(adminContext.request);
  const anonymous = await browser.newContext({ baseURL: 'http://127.0.0.1:5173' });

  familyA = await seed.createFamilyWithUser(anonymous.request, `E2E Familia A ${suffix}`, { nombre: 'Lucía', apellido: 'Prueba', email: `fam.a.${suffix}@example.test`, password: password() });
  familyB = await seed.createFamilyWithUser(anonymous.request, `E2E Familia B ${suffix}`, { nombre: 'Rosa', apellido: 'Prueba', email: `fam.b.${suffix}@example.test`, password: password() });
  await anonymous.close();

  for (let n = 1; n <= REVOKED; n += 1) {
    const created = await seed.createAthlete({
      nombre: NAME(n),
      apellido: 'Sintetico',
      ...(n === WITHOUT_BIRTH_DATE ? {} : { fechaNacimiento: '2014-03-10' }),
    });
    athleteIds.push(created.id);
    dnis.push(created.dni);
  }
  await seed.link(familyA.familyId, athleteIds);
  await seed.deactivate(athleteIds[INACTIVE - 1]!);
  await seed.revoke(familyA.familyId, athleteIds[REVOKED - 1]!);
});

test.afterAll(async () => {
  await adminContext.close();
});

test.describe('family portal (real backend)', () => {
  test('dashboard shows the real family, the real total and only a short preview', async () => {
    const { context, page } = await newContext();
    const portalRequests = new Set<string>();
    page.on('request', (request) => {
      const url = new URL(request.url());
      if (url.pathname.startsWith('/api/familia/')) portalRequests.add(`${url.pathname}${url.search}`);
    });
    await loginFamilyUi(page, familyA);

    await expect(page.getByRole('heading', { name: 'Hola, Lucía' })).toBeVisible();
    await expect(page.getByText(familyA.name)).toBeVisible();
    await expect(page.getByText('12 vinculados')).toBeVisible();
    await expect(athleteCards(page)).toHaveCount(3);
    await expect(athleteCards(page).first()).toContainText(NAME(1));
    await expect(page.getByRole('link', { name: 'Ver todos los deportistas (12)' })).toBeVisible();
    // It downloads only what it shows: the family and a page of 3 athletes.
    expect([...portalRequests].sort()).toEqual(['/api/familia/deportistas?page=0&size=3', '/api/familia/mi-familia']);
    await context.close();
  });

  test('list: two real pages of 10 and 2, inactive marked, revoked link gone', async () => {
    const { context, page } = await newContext();
    await loginFamilyUi(page, familyA);
    await page.getByRole('navigation', { name: 'Principal' }).getByRole('link', { name: 'Deportistas' }).click();
    await expect(page).toHaveURL(/\/familia\/deportistas$/);

    await expect(page.getByText('Página 1 de 2 · 12 en total')).toBeVisible();
    await expect(athleteCards(page)).toHaveCount(10);
    await expect(athleteCards(page).first()).toContainText(NAME(1));
    await expect(athleteCards(page).nth(9)).toContainText(NAME(10));
    await expect(page.getByRole('button', { name: 'Anterior' })).toBeDisabled();

    const inactive = athleteCards(page).filter({ hasText: NAME(INACTIVE) });
    await expect(inactive.getByText('Estado: Inactivo', { exact: true })).toBeVisible(); // sr-only prefix + visible text
    await expect(athleteCards(page).filter({ hasText: NAME(1) }).getByText('Estado: Activo', { exact: true })).toBeVisible();
    await expect(athleteCards(page).filter({ hasText: NAME(1) })).toContainText(/\d+ años/);
    await expect(page.getByText(/estado del deportista en la escuela/)).toBeVisible();
    await expect(page.getByText(/inscripto|habilitad/i)).toHaveCount(0);

    await page.getByRole('button', { name: 'Siguiente' }).click();
    await expect(page.getByText('Página 2 de 2 · 12 en total')).toBeVisible();
    await expect(athleteCards(page)).toHaveCount(2);
    await expect(athleteCards(page).nth(0)).toContainText(NAME(11));
    await expect(athleteCards(page).nth(1)).toContainText(NAME(WITHOUT_BIRTH_DATE));
    await expect(athleteCards(page).nth(1)).toContainText('Edad no informada');
    await expect(page.getByRole('button', { name: 'Siguiente' })).toBeDisabled();
    await expect(page.getByText(NAME(REVOKED))).toHaveCount(0); // revoked link: gone from both pages
    await context.close();
  });

  test('privacy: the API and the screen carry no DNI, CUIL, address or identifiers; nothing in storage or console', async () => {
    const { context, page } = await newContext();
    const bodies: string[] = [];
    const logged: { type: string; text: string }[] = [];
    page.on('console', (message) => logged.push({ type: message.type(), text: message.text() }));
    page.on('response', async (response) => {
      if (new URL(response.url()).pathname.startsWith('/api/familia/')) bodies.push(await response.text());
    });
    await loginFamilyUi(page, familyA);
    await page.goto('/familia/deportistas');
    await expect(page.getByText('Página 1 de 2 · 12 en total')).toBeVisible();

    const everything = `${bodies.join('\n')}\n${await page.content()}\n${await page.locator('body').innerText()}`;
    for (const dni of dnis) expect(everything).not.toContain(dni);
    expect(everything).not.toMatch(/"(dni|cuil|domicilio|telefonoContacto|escuelaId|familiaId)"/);
    expect(everything).not.toMatch(/cuil|domicilio/i);
    const athleteKeys = bodies.flatMap((body) => (JSON.parse(body) as { contenido?: object[] }).contenido ?? []).flatMap((a) => Object.keys(a));
    expect(new Set(athleteKeys)).toEqual(new Set(['id', 'nombre', 'apellido', 'fechaNacimiento', 'activo']));
    expect(await page.evaluate(() => ({ local: Object.keys(localStorage), session: Object.keys(sessionStorage) }))).toEqual({ local: [], session: [] });
    // The app logs nothing. The only tolerated console line is the browser's own note about the EXPECTED 401 of
    // GET /api/auth/me when the login screen is opened without a session.
    const expected401 = /^Failed to load resource: the server responded with a status of 401/;
    expect(logged.filter(({ type, text }) => (type === 'log' || type === 'error' || type === 'warning') && !expected401.test(text))).toEqual([]);
    await context.close();
  });

  test('responsive: no horizontal overflow at 360 px, bottom navigation works and never covers the logout button', async () => {
    const { context, page } = await newContext();
    await page.setViewportSize({ width: 360, height: 740 });
    await loginFamilyUi(page, familyA);

    expect(await noHorizontalOverflow(page)).toBe(true);
    const nav = page.getByRole('navigation', { name: 'Principal' });
    const box = (await nav.boundingBox())!;
    expect(Math.round(box.y + box.height)).toBe(740); // pinned to the bottom edge
    expect(box.height).toBeGreaterThanOrEqual(44);
    await expect(nav.getByRole('link', { name: 'Inicio' })).toHaveAttribute('aria-current', 'page');

    await nav.getByRole('link', { name: 'Deportistas' }).click();
    await expect(page.getByText('Página 1 de 2 · 12 en total')).toBeVisible();
    expect(await noHorizontalOverflow(page)).toBe(true);
    await expect(nav.getByRole('link', { name: 'Deportistas' })).toHaveAttribute('aria-current', 'page');

    const logout = page.getByRole('button', { name: 'Cerrar sesión' });
    await logout.scrollIntoViewIfNeeded();
    const covered = await logout.evaluate((el) => {
      const r = el.getBoundingClientRect();
      const top = document.elementFromPoint(r.left + r.width / 2, r.top + r.height / 2);
      return !(top === el || el.contains(top));
    });
    expect(covered).toBe(false);
    await context.close();
  });

  test('desktop: lateral navigation from md, evidence screenshots at 390 and 1280 px', async () => {
    const { context, page } = await newContext();
    await page.setViewportSize({ width: 1280, height: 800 });
    await loginFamilyUi(page, familyA);
    await page.getByRole('navigation', { name: 'Principal' }).getByRole('link', { name: 'Deportistas' }).click();
    await expect(page.getByText('Página 1 de 2 · 12 en total')).toBeVisible();

    const nav = (await page.getByRole('navigation', { name: 'Principal' }).boundingBox())!;
    const main = (await page.getByRole('main').boundingBox())!;
    expect(nav.x + nav.width).toBeLessThanOrEqual(main.x); // beside the content, not under it
    expect(nav.width).toBeLessThan(300);
    expect(await noHorizontalOverflow(page)).toBe(true);
    const mask = [page.getByText(/^Sesión de/)]; // the e-mail is synthetic, but evidence never shows account data
    await page.screenshot({ path: `${ARTIFACTS}/portal-athletes-desktop.png`, mask });

    await page.setViewportSize({ width: 390, height: 844 });
    await page.screenshot({ path: `${ARTIFACTS}/portal-athletes-mobile.png`, mask });
    await page.goto('/familia');
    await expect(page.getByText('12 vinculados')).toBeVisible();
    await page.screenshot({ path: `${ARTIFACTS}/portal-dashboard-mobile.png`, mask });
    await context.close();
  });

  test('isolation: family B sees none of A, and the backend answers a foreign id like a missing one', async () => {
    const { context, page } = await newContext();
    await loginFamilyUi(page, familyB);

    await expect(page.getByRole('heading', { name: 'Hola, Rosa' })).toBeVisible();
    await expect(page.getByText(familyB.name)).toBeVisible();
    await expect(page.getByText('Todavía no hay deportistas vinculados a tu familia')).toBeVisible();
    await page.goto('/familia/deportistas');
    await expect(page.getByText('Todavía no hay deportistas vinculados a tu familia')).toBeVisible();
    expect(await page.content()).not.toContain('E2E-Atleta');

    const api = context.request;
    const own = (await (await api.get('/api/familia/deportistas')).json()) as { contenido: unknown[]; totalElementos: number };
    expect(own).toMatchObject({ contenido: [], totalElementos: 0 });
    // The family always comes from the session: a familiaId parameter is ignored.
    const spoofed = (await (await api.get(`/api/familia/deportistas?familiaId=${familyA.familyId}`)).json()) as { totalElementos: number };
    expect(spoofed.totalElementos).toBe(0);

    const foreign = await api.get(`/api/familia/deportistas/${athleteIds[0]}`);
    const missing = await api.get(`/api/familia/deportistas/${randomUUID()}`);
    expect(foreign.status()).toBe(404);
    expect(missing.status()).toBe(404);
    expect(await foreign.json()).toEqual(await missing.json());
    expect(((await foreign.json()) as { codigo: string }).codigo).toBe('DEPORTISTA_NO_ENCONTRADO');

    // The administrator is not a family: the portal API is for ROLE_FAMILIA only.
    expect((await adminContext.request.get('/api/familia/mi-familia')).status()).toBe(403);
    await context.close();
  });

  test('same browser, two families: signing out and in as the other family never shows the first one', async () => {
    const { context, page } = await newContext();
    await loginFamilyUi(page, familyA);
    await expect(page.getByText('12 vinculados')).toBeVisible();

    await page.getByRole('button', { name: 'Cerrar sesión' }).click();
    await expect(page).toHaveURL(/\/login$/);
    await loginFamilyUi(page, familyB);

    await expect(page.getByRole('heading', { name: 'Hola, Rosa' })).toBeVisible();
    await expect(page.getByText('Todavía no hay deportistas vinculados a tu familia')).toBeVisible();
    const text = await page.locator('body').innerText();
    expect(text).not.toContain('E2E-Atleta');
    expect(text).not.toContain(familyA.name);
    await context.close();
  });

  test('an expired session sends the user to login, hides the data and, after signing in, returns to the page', async () => {
    const { context, page } = await newContext();
    await loginFamilyUi(page, familyA);
    await page.goto('/familia/deportistas');
    await expect(page.getByText('Página 1 de 2 · 12 en total')).toBeVisible();

    await context.clearCookies(); // the session is gone on the server's side as far as the browser is concerned
    await page.getByRole('button', { name: 'Siguiente' }).click(); // a new request: 401 NO_AUTENTICADO

    await expect(page.getByRole('heading', { name: 'Ingresá a tu portal' })).toBeVisible();
    expect(await page.locator('body').innerText()).not.toContain('E2E-Atleta');

    await page.getByLabel(/^Correo electrónico/).fill(familyA.email);
    await page.getByLabel(/^Contraseña/).fill(familyA.password);
    await page.getByRole('button', { name: 'Ingresar' }).click();
    await expect(page).toHaveURL(/\/familia\/deportistas$/); // back where the user was, not on the dashboard
    await expect(page.getByText('Página 1 de 2 · 12 en total')).toBeVisible();
    await context.close();
  });

  test('SIMULATED outage (page.route, not the real server): retryable error, session kept', async () => {
    const { context, page } = await newContext();
    await loginFamilyUi(page, familyA);
    await page.route('**/api/familia/deportistas?page=0&size=10', (route) => route.abort('connectionrefused'));
    await page.getByRole('navigation', { name: 'Principal' }).getByRole('link', { name: 'Deportistas' }).click();

    await expect(page.getByText(/No pudimos conectar con el servidor/)).toBeVisible();
    await expect(page.getByRole('button', { name: 'Cerrar sesión' })).toBeVisible(); // still signed in

    await page.unroute('**/api/familia/deportistas?page=0&size=10');
    await page.getByRole('button', { name: 'Reintentar' }).click();
    await expect(page.getByText('Página 1 de 2 · 12 en total')).toBeVisible();
    await context.close();
  });
});
