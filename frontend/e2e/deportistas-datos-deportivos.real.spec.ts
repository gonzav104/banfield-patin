import { randomBytes, randomUUID } from 'node:crypto';
import { expect, test, type BrowserContext, type Page } from '@playwright/test';
import { AdminSeed, loginAdminUi, syntheticDni, type SeededFamily } from './support/seed';

/**
 * REAL flows of the administrative sports data screens (FE-05 / U10-A: history and season detail, READ ONLY): browser -> Vite proxy ->
 * Spring backend -> PostgreSQL, against the contract of GET /api/admin/temporadas and GET /api/admin/deportistas/{id}/temporadas[/{temporadaId}].
 * Requires the administrator enrolled by auth.real.spec.ts (secret in e2e/.state.json). Everything is synthetic and created through the
 * real admin API (the seed already creates seasons and sports rows; nothing was added to it). School isolation between two schools is
 * covered by the backend DB tests (a second school cannot be created through the admin API).
 *
 * The administrator signs in ONCE for the whole file (one sign-in costs one TOTP window). Only the tests labelled "simulated" use
 * page.route: «the school has no seasons at all» cannot be produced with the real API here (seasons are never deleted and other
 * flows create them), and a service outage cannot be produced on demand. No traces, screenshots or videos are recorded (see
 * playwright.config.ts): names, categories and runner numbers never reach artifacts.
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

/** Seasons that no other spec uses; they are only created, never activated. */
const YEARS = { older: 2031, newer: 2032, empty: 2033 } as const;

const DATA = {
  older: { categoria: `Sub 14 ${suffix}`, rama: 'Femenino', divisional: 'A', numeroCorredor: `R${suffix}`, estadoDeportivo: 'SUSPENDIDO' as const },
  newer: { categoria: `Sub 16 ${suffix}`, estadoDeportivo: 'ACTIVO' as const },
};

let athlete = { id: '', nombre: `Lucia ${suffix}`, apellido: `Deportiva ${suffix}`, dni: syntheticDni() };
let inactiveAthlete = { id: '', nombre: `Ivan ${suffix}`, apellido: `Inactivo ${suffix}`, dni: syntheticDni() };
let emptyAthlete = { id: '', nombre: `Eva ${suffix}`, apellido: `Sinhistorial ${suffix}`, dni: syntheticDni() };
let seasons = { older: '', newer: '', empty: '' };

const historyPath = (id: string) => `/admin/deportistas/${id}/temporadas`;
const noHorizontalOverflow = () => page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth);
const mainText = () => page.getByRole('main').innerText();

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

  seasons = {
    older: (await seed.season(YEARS.older)).id,
    newer: (await seed.season(YEARS.newer)).id,
    empty: (await seed.season(YEARS.empty)).id,
  };
  athlete = { ...athlete, id: (await seed.createAthleteWith({ dni: athlete.dni, nombre: athlete.nombre, apellido: athlete.apellido })).id };
  inactiveAthlete = { ...inactiveAthlete, id: (await seed.createAthleteWith({ dni: inactiveAthlete.dni, nombre: inactiveAthlete.nombre, apellido: inactiveAthlete.apellido })).id };
  emptyAthlete = { ...emptyAthlete, id: (await seed.createAthleteWith({ dni: emptyAthlete.dni, nombre: emptyAthlete.nombre, apellido: emptyAthlete.apellido })).id };
  await seed.addSportsRow(athlete.id, seasons.older, DATA.older);
  await seed.addSportsRow(athlete.id, seasons.newer, DATA.newer);
  await seed.addSportsRow(inactiveAthlete.id, seasons.newer, { categoria: `Sub 12 ${suffix}`, estadoDeportivo: 'ACTIVO' });
  await seed.deactivate(inactiveAthlete.id);

  const anonymous = await browser.newContext({ baseURL: 'http://127.0.0.1:5173' });
  intruder = await seed.createFamilyWithUser(anonymous.request, `${base} usuaria`, {
    nombre: 'Ines',
    apellido: 'Intrusa',
    email: `datosdep.${suffix}@example.test`,
    password: `Fam-${randomBytes(8).toString('hex')}`,
  });
  await anonymous.close();
});

test.afterAll(async () => {
  await adminContext.close();
});

test.describe('sports history (real backend)', () => {
  test('is reached from the athlete detail, lists the seasons newest first and goes back', async () => {
    await page.goto(`/admin/deportistas/${athlete.id}`);
    const section = page.getByRole('region', { name: 'Datos deportivos' });
    await expect(section).toBeVisible();
    await section.getByRole('link', { name: 'Ver datos deportivos' }).click();
    await expect(page).toHaveURL(new RegExp(`${historyPath(athlete.id)}$`));
    await expect(page.getByRole('heading', { level: 1, name: 'Datos deportivos' })).toBeVisible();

    const items = page.getByRole('list', { name: 'Historial deportivo' }).getByRole('listitem');
    await expect(items).toHaveCount(2);
    await expect(items.nth(0).getByRole('heading', { level: 2 })).toHaveText(`Temporada ${YEARS.newer}`);
    await expect(items.nth(1).getByRole('heading', { level: 2 })).toHaveText(`Temporada ${YEARS.older}`);
    // Every sports field of the older row, as the backend stores it, and «No informado» for what the newer row does not have.
    await expect(items.nth(1)).toContainText(DATA.older.categoria);
    await expect(items.nth(1)).toContainText('Femenino');
    await expect(items.nth(1)).toContainText(DATA.older.numeroCorredor);
    await expect(items.nth(1)).toContainText('Suspendido');
    await expect(items.nth(0)).toContainText(DATA.newer.categoria);
    await expect(items.nth(0)).toContainText('No informado');

    await page.getByRole('link', { name: 'Volver al detalle del deportista' }).click();
    await expect(page.getByRole('heading', { level: 1, name: `${athlete.nombre} ${athlete.apellido}` })).toBeVisible();
  });

  test('tells the state in the school apart from the sports state of each year', async () => {
    await page.goto(historyPath(inactiveAthlete.id));
    await expect(page.getByRole('list', { name: 'Historial deportivo' })).toBeVisible();

    await expect(page.getByText('Estado en la escuela:').locator('..')).toContainText('Inactivo');
    await expect(page.getByText(`Estado deportivo de ${YEARS.newer}:`).locator('..')).toContainText('Activo');
    await expect(page.getByText(/«Estado en la escuela» indica si el deportista figura activo o inactivo en la escuela/)).toBeVisible();
  });

  test('says the athlete has no sports data when the school does have seasons', async () => {
    await page.goto(historyPath(emptyAthlete.id));

    await expect(page.getByRole('heading', { name: 'Este deportista todavía no tiene datos deportivos' })).toBeVisible();
    await expect(page.getByRole('heading', { name: 'Todavía no hay temporadas' })).toHaveCount(0);
    await expect(page.getByRole('main').getByRole('button')).toHaveCount(0);
    await expect(page.getByRole('main').getByRole('link', { name: /crear|nueva|cargar|agregar|activar/i })).toHaveCount(0);
  });

  test('simulated: a school without any season gets its own message and no way to create one', async () => {
    await page.route('**/api/admin/temporadas?page=0&size=1', (route) =>
      route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ contenido: [], pagina: 0, tamanio: 1, totalElementos: 0 }) }),
    );
    try {
      await page.goto(historyPath(emptyAthlete.id));
      await expect(page.getByRole('heading', { name: 'Todavía no hay temporadas' })).toBeVisible();
      await expect(page.getByRole('main').getByRole('link', { name: /crear|nueva|temporada|activar/i })).toHaveCount(0);
    } finally {
      await page.unroute('**/api/admin/temporadas?page=0&size=1');
    }
  });

  test('simulated outage: a fixed message, no data on screen and a retry that works', async () => {
    await page.route(`**/api/admin/deportistas/${athlete.id}/temporadas`, (route) => route.fulfill({ status: 503, contentType: 'application/json', body: '{"codigo":"NO_DISPONIBLE"}' }), { times: 1 });
    await page.goto(historyPath(athlete.id));

    await expect(page.getByText('El servicio no está disponible por el momento. Intentá de nuevo en unos instantes.')).toBeVisible();
    await expect(page.getByRole('list', { name: 'Historial deportivo' })).toHaveCount(0);
    await page.getByRole('button', { name: 'Reintentar' }).click();
    await expect(page.getByRole('list', { name: 'Historial deportivo' })).toBeVisible();
  });
});

test.describe('season detail (real backend)', () => {
  test('shows the sports data of that year and never the revision', async () => {
    await page.goto(historyPath(athlete.id));
    await page.getByRole('link', { name: `Ver detalle de la temporada ${YEARS.older}` }).click();
    await expect(page).toHaveURL(new RegExp(`${historyPath(athlete.id)}/${seasons.older}$`));
    await expect(page.getByRole('heading', { level: 1, name: `Temporada ${YEARS.older}` })).toBeVisible();

    const data = page.getByRole('region', { name: `Datos de la temporada ${YEARS.older}` });
    await expect(data.getByText('Categoría:').locator('..')).toContainText(DATA.older.categoria);
    await expect(data.getByText('Rama:').locator('..')).toContainText('Femenino');
    await expect(data.getByText('Divisional:').locator('..')).toContainText('A');
    await expect(data.getByText('Número de corredor:').locator('..')).toContainText(DATA.older.numeroCorredor);
    await expect(data.getByText(`Estado deportivo de ${YEARS.older}:`).locator('..')).toContainText('Suspendido');
    expect(await mainText()).not.toMatch(/revisi[oó]n|versi[oó]n/i);
    await expect(page.getByRole('main').getByRole('button')).toHaveCount(0);

    // The real contract: the revision is a decimal string, which is what the screen validates and does not show.
    const real = (await (await adminContext.request.get(`/api/admin/deportistas/${athlete.id}/temporadas/${seasons.older}`)).json()) as { version: unknown };
    expect(typeof real.version === 'string' && /^[0-9]{1,19}$/.test(real.version)).toBe(true);

    await page.getByRole('link', { name: 'Volver al historial deportivo' }).click();
    await expect(page.getByRole('list', { name: 'Historial deportivo' })).toBeVisible();
  });

  test('tells apart a season that does not exist, a season without data for the athlete and an athlete that does not exist', async () => {
    await page.goto(`${historyPath(athlete.id)}/${randomUUID()}`);
    await expect(page.getByRole('heading', { name: 'No encontramos esta temporada' })).toBeVisible();

    await page.goto(`${historyPath(athlete.id)}/${seasons.empty}`);
    await expect(page.getByRole('heading', { name: 'Sin datos deportivos en esta temporada' })).toBeVisible();
    await expect(page.getByRole('link', { name: 'Volver al historial deportivo' })).toBeVisible();

    await page.goto(`${historyPath(randomUUID())}/${seasons.older}`);
    await expect(page.getByRole('heading', { name: 'No encontramos este deportista' })).toBeVisible();
    await page.goto(historyPath(randomUUID()));
    await expect(page.getByRole('heading', { name: 'No encontramos este deportista' })).toBeVisible();
  });

  test('never asks the backend for a malformed identifier', async () => {
    apiRequests.length = 0;
    await page.goto('/admin/deportistas/x%20y/temporadas');
    await expect(page.getByRole('heading', { name: 'No encontramos este deportista' })).toBeVisible();
    await page.goto(`${historyPath(athlete.id)}/..%2Fx%20y`);
    await expect(page.getByRole('heading', { name: 'No encontramos esta temporada' })).toBeVisible();

    expect(apiRequests.filter((request) => /\/api\/admin\/(deportistas|temporadas)/.test(request))).toEqual([]);
  });
});

test.describe('permissions, privacy and layout (real backend)', () => {
  test('anonymous and family access are rejected and reveal nothing', async ({ browser, request }) => {
    for (const path of [`/api/admin/deportistas/${athlete.id}/temporadas`, `/api/admin/deportistas/${athlete.id}/temporadas/${seasons.older}`, '/api/admin/temporadas']) {
      expect((await request.get(path)).status(), path).toBe(401);
    }

    const familyContext = await browser.newContext({ baseURL: 'http://127.0.0.1:5173' });
    const familyPage = await familyContext.newPage();
    await familyPage.goto('/login');
    await familyPage.getByLabel(/^Correo electrónico/).fill(intruder.email);
    await familyPage.getByLabel(/^Contraseña/).fill(intruder.password);
    await familyPage.getByRole('button', { name: 'Ingresar' }).click();
    await expect(familyPage).toHaveURL(/\/familia$/);
    for (const path of [`/api/admin/deportistas/${athlete.id}/temporadas`, `/api/admin/deportistas/${athlete.id}/temporadas/${seasons.older}`, '/api/admin/temporadas?page=0&size=1']) {
      expect((await familyContext.request.get(path)).status(), path).toBe(403);
    }
    for (const path of [historyPath(athlete.id), `${historyPath(athlete.id)}/${seasons.older}`]) {
      await familyPage.goto(path);
      await expect(familyPage.getByRole('heading', { name: 'No tenés permiso para ver esta sección' })).toBeVisible();
    }
    const body = await familyPage.locator('body').innerText();
    for (const secret of [athlete.nombre, DATA.older.categoria, DATA.older.numeroCorredor]) expect(body).not.toContain(secret);
    await familyContext.close();
  });

  test('the history and the season fit a 360 px phone without horizontal scrolling, even with very long values', async () => {
    const long = await seed.createAthleteWith({ nombre: 'Nombre bastante largo para forzar el ajuste de linea', apellido: `Apellido igualmente extenso para pantallas chicas ${suffix}`, dni: syntheticDni() });
    await seed.addSportsRow(long.id, seasons.newer, { categoria: `C${'x'.repeat(78)}`, rama: 'R'.repeat(40), divisional: `D${'y'.repeat(78)}`, numeroCorredor: 'N'.repeat(30) });
    await page.setViewportSize({ width: 360, height: 740 });
    try {
      await page.goto(historyPath(long.id));
      await expect(page.getByRole('list', { name: 'Historial deportivo' })).toBeVisible();
      expect(await noHorizontalOverflow()).toBe(true);
      await page.goto(`${historyPath(long.id)}/${seasons.newer}`);
      await expect(page.getByRole('heading', { level: 1, name: `Temporada ${YEARS.newer}` })).toBeVisible();
      expect(await noHorizontalOverflow()).toBe(true);
    } finally {
      await page.setViewportSize({ width: 1280, height: 720 });
    }
  });

  test('writes no personal or sports data to the address, the console or the browser storage', async () => {
    await page.goto(historyPath(athlete.id));
    await page.getByRole('link', { name: `Ver detalle de la temporada ${YEARS.older}` }).click();
    await expect(page.getByRole('heading', { level: 1, name: `Temporada ${YEARS.older}` })).toBeVisible();

    for (const value of [athlete.nombre, athlete.apellido, athlete.dni, DATA.older.categoria, DATA.older.numeroCorredor]) {
      expect(visitedUrls.join(' '), value).not.toContain(value);
      expect(visitedUrls.join(' '), value).not.toContain(encodeURIComponent(value));
      expect(consoleLines.join('\n'), value).not.toContain(value);
    }
    const stored = await page.evaluate(() => JSON.stringify([{ ...localStorage }, { ...sessionStorage }]));
    for (const value of [athlete.nombre, athlete.apellido, athlete.dni, DATA.older.categoria, DATA.older.numeroCorredor]) expect(stored, value).not.toContain(value);
    // The only reads: the athlete, its history and one season row (plus, when the history is empty, a one-row look at the seasons).
    const sports = apiRequests.filter((request) => /\/temporadas/.test(request));
    expect(sports.every((request) => request.startsWith('GET '))).toBe(true);
  });
});
