import { randomBytes, randomUUID } from 'node:crypto';
import { expect, test, type BrowserContext, type Page } from '@playwright/test';
import { AdminSeed, loginAdminUi, syntheticDni, type SeededFamily } from './support/seed';

/**
 * REAL flows of the administrative consultation of family <-> athlete links (FE-05 / U11-B, READ ONLY): browser -> Vite proxy -> Spring
 * backend -> PostgreSQL, against the contract of GET /api/admin/deportistas/{id}/vinculos and GET /api/admin/familias/{id}/deportistas.
 * Requires the administrator enrolled by auth.real.spec.ts (secret in e2e/.state.json). Everything is synthetic and created through the
 * real admin API (AdminSeed: link, revoke, deactivate, deactivateFamily; nothing was added to it). School isolation between two schools is
 * covered by the backend DB tests (a second school cannot be created through the admin API); identity isolation (a late answer after a
 * change of administrator or resource) is covered by the Vitest suite, because one more administrator sign-in costs one TOTP window.
 *
 * The administrator signs in ONCE for the whole file. Only the tests labelled "simulated" use page.route (a service outage cannot be
 * produced on demand). The screens are strictly read-only: the whole file asserts that no write reaches the API. No traces, screenshots or
 * videos are recorded (see playwright.config.ts): names never reach artifacts.
 */
test.describe.configure({ mode: 'serial' });

const suffix = randomBytes(3).toString('hex');
const base = `E2E ${suffix}`;

let adminContext: BrowserContext;
let page: Page;
let seed: AdminSeed;
let intruder: SeededFamily;

const consoleLines: string[] = [];
const visitedUrls: string[] = [];
const apiRequests: string[] = [];

const person = (nombre: string, apellido: string) => ({ id: '', nombre: `${nombre} ${suffix}`, apellido: `${apellido} ${suffix}`, dni: syntheticDni() });
const fullName = (p: { nombre: string; apellido: string }) => `${p.nombre} ${p.apellido}`;

// Athletes: A (revoked principal, two active links with NO principal), B (active principal + active non principal), C (inactive, active
// principal link), D (no links at all) and E (the only athlete of an inactive family).
const A = person('Lucia', 'Alfa');
const B = person('Bruno', 'Beta');
const C = person('Carla', 'Gamma');
const D = person('Dario', 'Delta');
const E = person('Elena', 'Epsilon');
const families = {
  one: { id: '', name: `${base} Familia Uno` },
  two: { id: '', name: `${base} Familia Dos` },
  three: { id: '', name: `${base} Familia Tres` },
  empty: { id: '', name: `${base} Familia Vacia` },
  inactive: { id: '', name: `${base} Familia Inactiva` },
};

const linksPath = (id: string) => `/admin/deportistas/${id}/vinculos`;
const noHorizontalOverflow = () => page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth);
const mainText = () => page.getByRole('main').innerText();
const group = (name: string) => page.getByRole('list', { name }).getByRole('listitem');
const linkReads = () => apiRequests.filter((request) => /\/vinculos$|\/familias\/[^/]+\/deportistas$/.test(request));

test.beforeAll(async ({ browser }) => {
  adminContext = await browser.newContext({ baseURL: 'http://127.0.0.1:5173' });
  page = await adminContext.newPage();
  page.on('console', (message) => consoleLines.push(message.text()));
  page.on('framenavigated', (frame) => visitedUrls.push(frame.url()));
  page.on('request', (request) => {
    const url = new URL(request.url());
    if (url.pathname.startsWith('/api/')) apiRequests.push(`${request.method()} ${url.pathname}${url.search}`);
  });
  await loginAdminUi(page);
  seed = new AdminSeed(adminContext.request);

  for (const key of ['one', 'two', 'three', 'empty', 'inactive'] as const) families[key].id = (await seed.createFamily(families[key].name)).id;
  for (const p of [A, B, C, D, E]) p.id = (await seed.createAthleteWith({ dni: p.dni, nombre: p.nombre, apellido: p.apellido })).id;

  // The first active link of an athlete is its principal; revoking never promotes another one.
  await seed.link(families.one.id, [A.id, B.id, C.id]);
  await seed.link(families.two.id, [A.id, B.id]);
  await seed.link(families.three.id, [A.id]);
  await seed.revoke(families.one.id, A.id);
  await seed.deactivate(C.id);
  await seed.link(families.inactive.id, [E.id]);
  const inactiveFamily = (await (await adminContext.request.get(`/api/admin/familias/${families.inactive.id}`)).json()) as { version: string };
  await seed.deactivateFamily(families.inactive.id, inactiveFamily.version);

  const anonymous = await browser.newContext({ baseURL: 'http://127.0.0.1:5173' });
  intruder = await seed.createFamilyWithUser(anonymous.request, `${base} usuaria`, {
    nombre: 'Ines',
    apellido: 'Intrusa',
    email: `vinculos.${suffix}@example.test`,
    password: `Fam-${randomBytes(8).toString('hex')}`,
  });
  await anonymous.close();
});

test.afterAll(async () => {
  await adminContext.close();
});

test.describe('links of an athlete (real backend)', () => {
  test('is reached from the athlete detail and groups the active links apart from the revoked one, with no principal', async () => {
    await page.goto(`/admin/deportistas/${A.id}`);
    const access = page.getByRole('region', { name: 'Familias vinculadas' });
    await expect(access).toBeVisible();
    await expect(access.getByRole('button')).toHaveCount(0);
    await access.getByRole('link', { name: 'Ver familias vinculadas' }).click();
    await expect(page).toHaveURL(new RegExp(`${linksPath(A.id)}$`));
    await expect(page.getByRole('heading', { level: 1, name: 'Familias vinculadas' })).toBeVisible();
    await expect(page.getByText(fullName(A))).toBeVisible();

    await expect(group('Vínculos activos')).toHaveCount(2);
    // The server orders the links of an athlete by the name of the family: «Dos» before «Tres».
    await expect(group('Vínculos activos').nth(0)).toContainText(families.two.name);
    await expect(group('Vínculos activos').nth(1)).toContainText(families.three.name);
    await expect(group('Vínculos anteriores')).toHaveCount(1);
    await expect(group('Vínculos anteriores').first()).toContainText(families.one.name);
    await expect(group('Vínculos anteriores').first()).toContainText('Vínculo revocado');
    await expect(page.getByText('Ninguna de las familias activas está marcada como principal.')).toBeVisible();
    await expect(page.getByText('Familia principal')).toHaveCount(0);
    await expect(page.getByText('Solo un vínculo activo le da acceso a la familia.')).toBeVisible();

    await page.getByRole('link', { name: 'Volver al detalle del deportista' }).click();
    await expect(page.getByRole('heading', { level: 1, name: fullName(A) })).toBeVisible();
  });

  test('marks as principal only the active principal family', async () => {
    await page.goto(linksPath(B.id));
    await expect(group('Vínculos activos')).toHaveCount(2);

    await expect(page.getByText('Familia principal')).toHaveCount(1);
    await expect(group('Vínculos activos').filter({ hasText: families.one.name })).toContainText('Familia principal');
    await expect(group('Vínculos activos').filter({ hasText: families.two.name })).not.toContainText('Familia principal');
    await expect(page.getByText('Ninguna de las familias activas está marcada como principal.')).toHaveCount(0);
    await expect(page.getByRole('list', { name: 'Vínculos anteriores' })).toHaveCount(0);
    expect(await mainText()).not.toMatch(/tutor principal/i);
  });

  test('an inactive athlete keeps its active link and is marked as inactive in the header only', async () => {
    await page.goto(linksPath(C.id));
    await expect(group('Vínculos activos')).toHaveCount(1);

    await expect(page.getByText('Estado en la escuela:').locator('..')).toContainText('Inactivo');
    await expect(page.getByText('Un deportista inactivo conserva sus vínculos activos: su familia lo sigue viendo, marcado como inactivo.')).toBeVisible();
    await expect(group('Vínculos activos').first()).toContainText('Vínculo activo');
    await expect(group('Vínculos activos').first()).toContainText('Familia principal');
  });

  test('an athlete with no links says so and offers nothing to create', async () => {
    await page.goto(linksPath(D.id));

    await expect(page.getByText('Este deportista todavía no está vinculado con ninguna familia.')).toBeVisible();
    await expect(page.getByRole('list')).toHaveCount(0);
    await expect(page.getByRole('main').getByRole('button')).toHaveCount(0);
    await expect(page.getByRole('main').getByRole('link', { name: /vincular|agregar|crear/i })).toHaveCount(0);
  });

  test('never shows the revision, and the real answer carries a decimal revision and no DNI or CUIL', async () => {
    await page.goto(linksPath(A.id));
    await expect(group('Vínculos activos')).toHaveCount(2);
    expect(await mainText()).not.toMatch(/revisi[oó]n|versi[oó]n/i);

    const real = (await (await adminContext.request.get(`/api/admin/deportistas/${A.id}/vinculos`)).json()) as { versionVinculos: unknown; vinculos: Record<string, unknown>[] };
    expect(typeof real.versionVinculos === 'string' && /^[0-9]{1,19}$/.test(real.versionVinculos)).toBe(true);
    expect(real.vinculos.length).toBe(3);
    for (const row of real.vinculos) {
      expect(row.versionVinculos).toBe(real.versionVinculos);
      expect(Object.keys(row)).not.toEqual(expect.arrayContaining(['dni']));
      expect(JSON.stringify(row)).not.toContain(A.dni);
    }
    expect(await page.locator('body').innerText()).not.toContain(A.dni);
  });
});

test.describe('athletes of a family (real backend)', () => {
  test('lists the athletes of the family in two groups, with the principal and the inactive athlete told apart', async () => {
    await page.goto(`/admin/familias/${families.one.id}`);
    const section = page.getByRole('region', { name: 'Deportistas vinculados' });
    await expect(section).toBeVisible();
    await expect(section.getByText('Solo un vínculo activo les da acceso a los deportistas.')).toBeVisible();

    const active = section.getByRole('list', { name: 'Vínculos activos' }).getByRole('listitem');
    await expect(active).toHaveCount(2); // B and C (the server orders by surname: Beta, Gamma)
    await expect(active.nth(0)).toContainText(fullName(B));
    await expect(active.nth(0)).toContainText('Principal de este deportista');
    await expect(active.nth(0)).not.toContainText('Deportista inactivo');
    await expect(active.nth(1)).toContainText(fullName(C));
    await expect(active.nth(1)).toContainText('Vínculo activo');
    await expect(active.nth(1)).toContainText('Principal de este deportista');
    await expect(active.nth(1)).toContainText('Deportista inactivo');
    const previous = section.getByRole('list', { name: 'Vínculos anteriores' }).getByRole('listitem');
    await expect(previous).toHaveCount(1);
    await expect(previous.first()).toContainText(fullName(A));
    await expect(previous.first()).toContainText('Vínculo revocado');
    await expect(previous.first()).not.toContainText('Principal de este deportista');
    await expect(section.getByRole('button', { name: 'Vincular deportistas' })).toBeEnabled();
    await expect(section.getByRole('button')).toHaveCount(1);
    expect(await section.innerText()).not.toMatch(/dni|cuil|revisi[oó]n|versi[oó]n/i);
  });

  test('walks family -> athlete -> links -> family through the links of both screens', async () => {
    await page.goto(`/admin/familias/${families.two.id}`);
    await page.getByRole('region', { name: 'Deportistas vinculados' }).getByRole('link', { name: fullName(A) }).click();
    await expect(page).toHaveURL(new RegExp(`/admin/deportistas/${A.id}$`));
    await expect(page.getByRole('heading', { level: 1, name: fullName(A) })).toBeVisible();

    await page.getByRole('link', { name: 'Ver familias vinculadas' }).click();
    await expect(page).toHaveURL(new RegExp(`${linksPath(A.id)}$`));
    await page.getByRole('link', { name: families.two.name }).click();
    await expect(page).toHaveURL(new RegExp(`/admin/familias/${families.two.id}$`));
    await expect(page.getByRole('heading', { level: 1, name: families.two.name })).toBeVisible();
    await expect(page.getByRole('region', { name: 'Deportistas vinculados' }).getByRole('listitem').first()).toBeVisible();
  });

  test('a family with no athletes says so', async () => {
    await page.goto(`/admin/familias/${families.empty.id}`);
    const section = page.getByRole('region', { name: 'Deportistas vinculados' });
    await expect(section.getByText('Esta familia todavía no tiene deportistas vinculados.')).toBeVisible();
    await expect(section.getByRole('list')).toHaveCount(0);
  });

  test('an inactive family keeps its active links and says it does not enter the portal', async () => {
    await page.goto(`/admin/familias/${families.inactive.id}`);
    const section = page.getByRole('region', { name: 'Deportistas vinculados' });
    await expect(section.getByText('La familia está inactiva: no entra al portal aunque sus vínculos sigan activos.')).toBeVisible();
    await expect(section.getByRole('list', { name: 'Vínculos activos' }).getByRole('listitem')).toHaveCount(1);
    await expect(section).toContainText(fullName(E));
    await expect(section).toContainText('Vínculo activo');
  });

  test('simulated outage: only the section fails, the family detail stays and a retry works', async () => {
    await page.route(`**/api/admin/familias/${families.one.id}/deportistas`, (route) => route.fulfill({ status: 503, contentType: 'application/json', body: '{"codigo":"NO_DISPONIBLE"}' }), { times: 1 });
    await page.goto(`/admin/familias/${families.one.id}`);

    const section = page.getByRole('region', { name: 'Deportistas vinculados' });
    await expect(section.getByText('El servicio no está disponible por el momento. Intentá de nuevo en unos instantes.')).toBeVisible();
    await expect(page.getByRole('heading', { level: 1, name: families.one.name })).toBeVisible();
    await expect(section.getByRole('list')).toHaveCount(0);
    await section.getByRole('button', { name: 'Reintentar' }).click();
    await expect(section.getByRole('list', { name: 'Vínculos activos' })).toBeVisible();
  });

  test('simulated outage: the screen of an athlete shows a fixed message and a retry that works', async () => {
    await page.route(`**/api/admin/deportistas/${B.id}/vinculos`, (route) => route.fulfill({ status: 503, contentType: 'application/json', body: '{"codigo":"NO_DISPONIBLE"}' }), { times: 1 });
    await page.goto(linksPath(B.id));

    await expect(page.getByText('El servicio no está disponible por el momento. Intentá de nuevo en unos instantes.')).toBeVisible();
    await expect(page.getByRole('list', { name: 'Vínculos activos' })).toHaveCount(0);
    await page.getByRole('button', { name: 'Reintentar' }).click();
    await expect(page.getByRole('list', { name: 'Vínculos activos' })).toBeVisible();
  });
});

test.describe('not found, permissions, privacy and layout (real backend)', () => {
  test('an athlete or a family that does not exist is the uniform «not found» screen', async () => {
    await page.goto(linksPath(randomUUID()));
    await expect(page.getByRole('heading', { name: 'No encontramos este deportista' })).toBeVisible();
    await expect(page.getByRole('button', { name: 'Reintentar' })).toHaveCount(0);

    await page.goto(`/admin/familias/${randomUUID()}`);
    await expect(page.getByRole('heading', { name: 'No encontramos esta familia' })).toBeVisible();
    await expect(page.getByRole('region', { name: 'Deportistas vinculados' })).toHaveCount(0);
  });

  test('never asks the backend for a malformed identifier', async () => {
    apiRequests.length = 0;
    await page.goto('/admin/deportistas/x%20y/vinculos');
    await expect(page.getByRole('heading', { name: 'No encontramos este deportista' })).toBeVisible();
    await page.goto('/admin/familias/x%20y');
    await expect(page.getByRole('heading', { name: 'No encontramos esta familia' })).toBeVisible();

    expect(apiRequests.filter((request) => /\/api\/admin\/(deportistas|familias)\/x/.test(request))).toEqual([]);
  });

  test('anonymous and family access are rejected and reveal nothing', async ({ browser, request }) => {
    const reads = [`/api/admin/deportistas/${A.id}/vinculos`, `/api/admin/familias/${families.one.id}/deportistas`];
    for (const path of reads) expect((await request.get(path)).status(), path).toBe(401);

    const familyContext = await browser.newContext({ baseURL: 'http://127.0.0.1:5173' });
    const familyPage = await familyContext.newPage();
    await familyPage.goto('/login');
    await familyPage.getByLabel(/^Correo electrónico/).fill(intruder.email);
    await familyPage.getByLabel(/^Contraseña/).fill(intruder.password);
    await familyPage.getByRole('button', { name: 'Ingresar' }).click();
    await expect(familyPage).toHaveURL(/\/familia$/);
    for (const path of reads) expect((await familyContext.request.get(path)).status(), path).toBe(403);
    for (const path of [linksPath(A.id), `/admin/familias/${families.one.id}`]) {
      await familyPage.goto(path);
      await expect(familyPage.getByRole('heading', { name: 'No tenés permiso para ver esta sección' })).toBeVisible();
    }
    const body = await familyPage.locator('body').innerText();
    for (const secret of [A.nombre, B.nombre, families.one.name]) expect(body).not.toContain(secret);
    await familyContext.close();
  });

  test('both screens fit a 360 px phone without horizontal scrolling, even with very long names', async () => {
    const long = await seed.createAthleteWith({ nombre: 'Nombre bastante largo para forzar el ajuste de linea', apellido: `Apellido igualmente extenso para pantallas chicas ${suffix}`, dni: syntheticDni() });
    const longFamily = await seed.createFamily(`${base} ${'Familia con un nombre de referencia larguisimo '.repeat(3)}`.slice(0, 150));
    await seed.link(longFamily.id, [long.id]);
    await seed.link(families.two.id, [long.id]);
    await page.setViewportSize({ width: 360, height: 740 });
    try {
      await page.goto(linksPath(long.id));
      await expect(group('Vínculos activos')).toHaveCount(2);
      expect(await noHorizontalOverflow()).toBe(true);
      await page.goto(`/admin/familias/${longFamily.id}`);
      await expect(page.getByRole('region', { name: 'Deportistas vinculados' }).getByRole('listitem')).toHaveCount(1);
      expect(await noHorizontalOverflow()).toBe(true);
      await page.goto(linksPath(A.id));
      await expect(group('Vínculos anteriores')).toHaveCount(1);
      expect(await noHorizontalOverflow()).toBe(true);
    } finally {
      await page.setViewportSize({ width: 1280, height: 720 });
    }
  });

  test('writes no personal data to the address, the console or the browser storage, and sends no write', async () => {
    apiRequests.length = 0;
    await page.goto(`/admin/deportistas/${A.id}`);
    await page.getByRole('link', { name: 'Ver familias vinculadas' }).click();
    await expect(group('Vínculos activos')).toHaveCount(2);
    await page.getByRole('link', { name: families.two.name }).click();
    await expect(page.getByRole('region', { name: 'Deportistas vinculados' }).getByRole('listitem').first()).toBeVisible();

    for (const value of [A.nombre, A.apellido, A.dni, families.one.name, families.two.name]) {
      expect(visitedUrls.join(' '), value).not.toContain(value);
      expect(visitedUrls.join(' '), value).not.toContain(encodeURIComponent(value));
      expect(consoleLines.join('\n'), value).not.toContain(value);
    }
    const stored = await page.evaluate(() => JSON.stringify([{ ...localStorage }, { ...sessionStorage }]));
    for (const value of [A.nombre, A.apellido, A.dni, families.one.name, families.two.name]) expect(stored, value).not.toContain(value);
    // Every request the screens made to the API is a read.
    expect(apiRequests.filter((request) => /\/api\/admin\//.test(request)).every((request) => request.startsWith('GET '))).toBe(true);
    expect(linkReads().length).toBeGreaterThan(0);
  });
});
