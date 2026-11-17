import { randomBytes } from 'node:crypto';
import { expect, test, type BrowserContext, type Page } from '@playwright/test';
import { AdminSeed, csrfHeaders, loginAdminUi, syntheticDni, type SeededFamily } from './support/seed';

/**
 * REAL flows of the administrative LOAD of sports data (FE-05 / U10-B): browser -> Vite proxy -> Spring backend -> PostgreSQL, against
 * the contract of POST /api/admin/deportistas/{id}/temporadas and the lists it needs (GET /api/admin/temporadas and the history).
 * Requires the administrator enrolled by auth.real.spec.ts (secret in e2e/.state.json). Everything is synthetic and created through
 * the real admin API with the helpers the seed already has (nothing was added to it). School isolation between two schools is covered
 * by the backend DB tests (a second school cannot be created through the admin API).
 *
 * The administrator signs in ONCE for the whole file (one sign-in costs one TOTP window). Only the tests labelled "simulated" use
 * page.route to FAKE an answer (a school without seasons, which cannot be produced here because seasons are never deleted). The
 * uncertain-answer tests use page.route to cut the connection while the REAL backend does (or does not) apply the request, so the
 * rest of what they show is real. No traces, screenshots or videos are recorded (see playwright.config.ts): names, categories and
 * runner numbers never reach artifacts.
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

/** Seasons no other spec uses; they are only created (and one activated), never deleted. */
const YEARS = { used: 2041, active: 2042, inactive: 2043 } as const;
let seasons = { used: '', active: '', inactive: '' };

const person = (label: string) => ({ nombre: `${label} ${suffix}`, apellido: `Alta ${suffix}`, dni: syntheticDni() });
let main = { id: '', ...person('Lucia') };
let other = { id: '', ...person('Ana') };
let inactiveAthlete = { id: '', ...person('Ivan') };
let fullAthlete = { id: '', ...person('Eva') };

const historyPath = (id: string) => `/admin/deportistas/${id}/temporadas`;
const loadPath = (id: string) => `${historyPath(id)}/nueva`;
const noHorizontalOverflow = () => page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth);
const seasonSelect = () => page.getByLabel(/^Temporada/);
const submit = () => page.getByRole('button', { name: /^(Cargar datos|Cargando…)$/ });
const numberOf = (seed2: string) => `${seed2}${suffix}`;
const posts = () => apiRequests.filter((request) => request.startsWith('POST ') && request.endsWith('/temporadas'));

/** What the backend really has for an athlete, through the admin API. */
async function storedRows(athleteId: string): Promise<{ temporadaId: string; categoria: string | null; numeroCorredor: string | null; estadoDeportivo: string; anio: number }[]> {
  const response = await adminContext.request.get(`/api/admin/deportistas/${athleteId}/temporadas`);
  expect(response.ok()).toBe(true);
  return (await response.json()) as never;
}

/** The seasons of this spec the selector offers, once the form is really there (the options only exist after the lists were read). */
async function offeredSeasons(): Promise<string[]> {
  await expect(page.getByRole('form', { name: 'Cargar datos deportivos' })).toBeVisible();
  return (await seasonSelect().locator('option').allTextContents()).filter((label) => /^Temporada 204/.test(label));
}

async function openLoadForm(athleteId: string) {
  await page.goto(loadPath(athleteId));
  await expect(page.getByRole('form', { name: 'Cargar datos deportivos' })).toBeVisible();
}

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
    used: (await seed.season(YEARS.used)).id,
    active: (await seed.season(YEARS.active)).id,
    inactive: (await seed.season(YEARS.inactive)).id,
  };
  await seed.activateSeason(seasons.active);
  main = { ...main, id: (await seed.createAthleteWith({ dni: main.dni, nombre: main.nombre, apellido: main.apellido })).id };
  other = { ...other, id: (await seed.createAthleteWith({ dni: other.dni, nombre: other.nombre, apellido: other.apellido })).id };
  inactiveAthlete = { ...inactiveAthlete, id: (await seed.createAthleteWith({ dni: inactiveAthlete.dni, nombre: inactiveAthlete.nombre, apellido: inactiveAthlete.apellido })).id };
  fullAthlete = { ...fullAthlete, id: (await seed.createAthleteWith({ dni: fullAthlete.dni, nombre: fullAthlete.nombre, apellido: fullAthlete.apellido })).id };
  await seed.addSportsRow(main.id, seasons.used, { categoria: `Previa ${suffix}` });
  await seed.deactivate(inactiveAthlete.id);

  const anonymous = await browser.newContext({ baseURL: 'http://127.0.0.1:5173' });
  intruder = await seed.createFamilyWithUser(anonymous.request, `${base} usuaria`, {
    nombre: 'Ines',
    apellido: 'Intrusa',
    email: `alta.${suffix}@example.test`,
    password: `Fam-${randomBytes(8).toString('hex')}`,
  });
  await anonymous.close();
});

test.afterAll(async () => {
  await adminContext.close();
});

test.describe('the selector and a confirmed load (real backend)', () => {
  test('is reached from the history and offers only the seasons without data, the active one flagged and the inactive ones too', async () => {
    await page.goto(historyPath(main.id));
    await page.getByRole('link', { name: 'Cargar datos de una temporada' }).click();
    await expect(page).toHaveURL(new RegExp(`${loadPath(main.id)}$`));
    await expect(page.getByRole('heading', { level: 1, name: 'Cargar datos deportivos' })).toBeVisible();

    expect(await offeredSeasons()).toEqual([`Temporada ${YEARS.inactive}`, `Temporada ${YEARS.active} (activa)`]); // newest first; the used one (2041) is not offered
    expect(await seasonSelect().locator('option').first().textContent()).toBe('Elegí una temporada');
  });

  test('loads every field, keeps the leading zeros and goes to the detail with a notice; the history shows it and the season is no longer offered', async () => {
    const number = numberOf('007');
    await openLoadForm(main.id);
    await seasonSelect().selectOption({ label: `Temporada ${YEARS.active} (activa)` });
    await page.getByLabel(/^Categoría/).fill(`  Sub   14 ${suffix} `);
    await page.getByLabel(/^Rama/).fill('Femenino');
    await page.getByLabel(/^Divisional/).fill('A');
    await page.getByLabel(/^Número de corredor/).fill(number);
    await page.getByLabel(/^Estado deportivo/).selectOption('SUSPENDIDO');
    await submit().click();

    await expect(page).toHaveURL(new RegExp(`${historyPath(main.id)}/${seasons.active}$`));
    await expect(page.getByRole('heading', { level: 1, name: `Temporada ${YEARS.active}` })).toBeVisible();
    await expect(page.getByText('Datos deportivos cargados.')).toBeVisible();
    const data = page.getByRole('region', { name: `Datos de la temporada ${YEARS.active}` });
    await expect(data.getByText('Categoría:').locator('..')).toContainText(`Sub 14 ${suffix}`);
    await expect(data.getByText('Número de corredor:').locator('..')).toContainText(number);
    await expect(data.getByText(`Estado deportivo de ${YEARS.active}:`).locator('..')).toContainText('Suspendido');

    // What the backend really stored (normalised text, the number as typed, the chosen status).
    const stored = (await storedRows(main.id)).find((row) => row.temporadaId === seasons.active);
    expect(stored).toMatchObject({ categoria: `Sub 14 ${suffix}`, numeroCorredor: number, estadoDeportivo: 'SUSPENDIDO' });

    await page.getByRole('link', { name: 'Volver al historial deportivo' }).click();
    await expect(page.getByRole('list', { name: 'Historial deportivo' }).getByRole('heading', { level: 2 })).toHaveText([`Temporada ${YEARS.active}`, `Temporada ${YEARS.used}`]);
    await page.getByRole('link', { name: 'Cargar datos de una temporada' }).click();
    expect(await offeredSeasons()).toEqual([`Temporada ${YEARS.inactive}`]);
  });

  test('a season alone is enough: the status is ACTIVO and every optional stays empty', async () => {
    await openLoadForm(fullAthlete.id);
    await seasonSelect().selectOption({ label: `Temporada ${YEARS.inactive}` });
    await expect(page.getByLabel(/^Estado deportivo/)).toHaveValue('ACTIVO');
    await submit().click();

    await expect(page.getByRole('heading', { level: 1, name: `Temporada ${YEARS.inactive}` })).toBeVisible();
    await expect(page.getByRole('region', { name: `Datos de la temporada ${YEARS.inactive}` }).getByText('No informado')).toHaveCount(4);
    const stored = (await storedRows(fullAthlete.id)).find((row) => row.temporadaId === seasons.inactive);
    expect(stored).toMatchObject({ categoria: null, numeroCorredor: null, estadoDeportivo: 'ACTIVO' });
  });

  test('says every season is used when the athlete has data in all of them, and offers no form', async () => {
    const list = (await (await adminContext.request.get('/api/admin/temporadas?page=0&size=100')).json()) as { contenido: { id: string; anio: number }[] };
    const taken = new Set((await storedRows(fullAthlete.id)).map((row) => row.temporadaId));
    for (const season of list.contenido) if (!taken.has(season.id)) await seed.addSportsRow(fullAthlete.id, season.id, {});

    await page.goto(loadPath(fullAthlete.id));
    await expect(page.getByRole('heading', { name: 'Ya hay datos en todas las temporadas' })).toBeVisible();
    await expect(page.getByRole('form', { name: 'Cargar datos deportivos' })).toHaveCount(0);
  });

  test('simulated: a school without any season gets its own message and no way to create one', async () => {
    await page.route('**/api/admin/temporadas?page=0&size=100', (route) =>
      route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ contenido: [], pagina: 0, tamanio: 100, totalElementos: 0 }) }),
    );
    try {
      await page.goto(loadPath(other.id));
      await expect(page.getByRole('heading', { name: 'Todavía no hay temporadas' })).toBeVisible();
      await expect(page.getByRole('form', { name: 'Cargar datos deportivos' })).toHaveCount(0);
      await expect(page.getByRole('main').getByRole('link', { name: /crear|nueva|activar/i })).toHaveCount(0);
    } finally {
      await page.unroute('**/api/admin/temporadas?page=0&size=100');
    }
  });
});

test.describe('what the real backend refuses (real backend)', () => {
  test('a runner number already taken in that season is explained next to the number, the draft stays and a different number goes through', async () => {
    const taken = numberOf('T');
    await seed.addSportsRow(other.id, seasons.inactive, { numeroCorredor: taken });
    const athlete = await seed.createAthleteWith(person('Nora'));
    await openLoadForm(athlete.id);
    await seasonSelect().selectOption({ label: `Temporada ${YEARS.inactive}` });
    await page.getByLabel(/^Categoría/).fill(`Sub 16 ${suffix}`);
    await page.getByLabel(/^Número de corredor/).fill(taken);
    await submit().click();

    await expect(page.getByText('Ese número de corredor ya lo tiene otro deportista en esa temporada. Elegí otro o dejalo vacío.')).toBeVisible();
    await expect(page.getByLabel(/^Número de corredor/)).toHaveAttribute('aria-invalid', 'true');
    await expect(page.getByLabel(/^Categoría/)).toHaveValue(`Sub 16 ${suffix}`);
    expect(await storedRows(athlete.id)).toHaveLength(0);

    await page.getByLabel(/^Número de corredor/).fill(numberOf('U'));
    await submit().click();
    await expect(page.getByRole('heading', { level: 1, name: `Temporada ${YEARS.inactive}` })).toBeVisible();
    expect(await storedRows(athlete.id)).toHaveLength(1);
  });

  test('a season that got data while the form was open is refused as a duplicate, leaves the selector and keeps the draft', async () => {
    const athlete = await seed.createAthleteWith(person('Rita'));
    await openLoadForm(athlete.id);
    await seasonSelect().selectOption({ label: `Temporada ${YEARS.inactive}` });
    await page.getByLabel(/^Categoría/).fill(`Sub 18 ${suffix}`);
    await seed.addSportsRow(athlete.id, seasons.inactive, { categoria: 'Cargada por otra persona' }); // another administrator, after the form was drawn
    await submit().click();

    await expect(page.getByText(/Este deportista ya tiene datos deportivos en esa temporada\./)).toBeVisible();
    await expect(page.getByRole('link', { name: `Ver los datos de la temporada ${YEARS.inactive}` })).toBeVisible();
    await expect(page.getByLabel(/^Categoría/)).toHaveValue(`Sub 18 ${suffix}`);
    await expect(seasonSelect().locator('option', { hasText: `Temporada ${YEARS.inactive}` })).toHaveCount(0);
    expect((await storedRows(athlete.id)).map((row) => row.categoria)).toEqual(['Cargada por otra persona']);
  });

  test('an inactive athlete gets no form, and the real backend answers 409 DEPORTISTA_INACTIVO to a request that skips the screen', async () => {
    await page.goto(historyPath(inactiveAthlete.id));
    await expect(page.getByText('Un deportista inactivo no admite datos deportivos nuevos. Reactivalo desde su detalle.')).toBeVisible();
    await expect(page.getByRole('link', { name: 'Cargar datos de una temporada' })).toHaveCount(0);
    await page.goto(loadPath(inactiveAthlete.id));
    await expect(page.getByRole('heading', { name: 'Este deportista está inactivo' })).toBeVisible();
    await expect(page.getByRole('form', { name: 'Cargar datos deportivos' })).toHaveCount(0);

    const response = await adminContext.request.post(`/api/admin/deportistas/${inactiveAthlete.id}/temporadas`, {
      data: { temporadaId: seasons.used },
      headers: await csrfHeaders(adminContext.request),
    });
    expect(response.status()).toBe(409);
    expect(((await response.json()) as { codigo: string }).codigo).toBe('DEPORTISTA_INACTIVO');
    expect(await storedRows(inactiveAthlete.id)).toHaveLength(0);
  });
});

test.describe('requests and answers that cannot be confirmed (real backend)', () => {
  test('three quick clicks on «Cargar datos» send ONE request and create ONE row', async () => {
    const athlete = await seed.createAthleteWith(person('Sofia'));
    await openLoadForm(athlete.id);
    await seasonSelect().selectOption({ label: `Temporada ${YEARS.inactive}` });
    const before = posts().length;

    await submit().click({ clickCount: 3 });

    await expect(page.getByRole('heading', { level: 1, name: `Temporada ${YEARS.inactive}` })).toBeVisible();
    expect(posts().length - before).toBe(1);
    expect(await storedRows(athlete.id)).toHaveLength(1);
  });

  test('a connection cut AFTER the backend applied the request: nothing is claimed, the reading shows the confirmed row and does not attribute it to the request', async () => {
    const athlete = await seed.createAthleteWith(person('Tomas'));
    await openLoadForm(athlete.id);
    await seasonSelect().selectOption({ label: `Temporada ${YEARS.inactive}` });
    await page.getByLabel(/^Categoría/).fill(`Sub 20 ${suffix}`);
    await page.route(`**/api/admin/deportistas/${athlete.id}/temporadas`, async (route) => {
      if (route.request().method() !== 'POST') return route.continue();
      await route.fetch(); // the REAL backend applies it...
      return route.abort('failed'); // ...and the browser never sees the answer
    });
    try {
      await submit().click();
      await expect(page.getByText('No pudimos confirmar si los datos se cargaron.')).toBeVisible();
    } finally {
      await page.unroute(`**/api/admin/deportistas/${athlete.id}/temporadas`);
    }
    await expect(page.getByLabel(/^Categoría/)).toBeDisabled();
    await expect(page.getByLabel(/^Categoría/)).toHaveValue(`Sub 20 ${suffix}`);
    await expect(page.getByText('Datos deportivos cargados.')).toHaveCount(0);

    await page.getByRole('button', { name: 'Verificar la temporada' }).click();
    const group = page.getByRole('group', { name: 'Verificación' });
    await expect(group.getByText('La temporada ya tiene estos datos cargados:')).toBeVisible();
    await expect(group).toContainText(`Sub 20 ${suffix}`);
    await expect(group.getByText('Coinciden con lo que enviaste, pero no podemos saber si los cargó este envío u otro.')).toBeVisible();
    await expect(group.getByRole('button', { name: 'Volver a intentar' })).toHaveCount(0);
    expect(await storedRows(athlete.id)).toHaveLength(1);
  });

  test('a connection cut BEFORE the request reached the backend: the reading finds nothing, says it proves nothing, and only an explicit retry sends again', async () => {
    const athlete = await seed.createAthleteWith(person('Uma'));
    await openLoadForm(athlete.id);
    await seasonSelect().selectOption({ label: `Temporada ${YEARS.inactive}` });
    await page.getByLabel(/^Categoría/).fill(`Sub 22 ${suffix}`);
    await page.route(`**/api/admin/deportistas/${athlete.id}/temporadas`, (route) => (route.request().method() === 'POST' ? route.abort('failed') : route.continue()), { times: 1 });
    await submit().click();
    await expect(page.getByText('No pudimos confirmar si los datos se cargaron.')).toBeVisible();
    const sentOnce = posts().length;

    await page.getByRole('button', { name: 'Verificar la temporada' }).click();
    const group = page.getByRole('group', { name: 'Verificación' });
    await expect(group.getByText(/No encontramos datos cargados en esa temporada en este momento\./)).toBeVisible();
    await expect(group.getByText(/no descarta que el envío anterior se confirme más tarde/)).toBeVisible();
    expect(posts().length).toBe(sentOnce); // nothing was resent by the screen
    expect(await storedRows(athlete.id)).toHaveLength(0);

    await group.getByRole('button', { name: 'Volver a intentar' }).click();
    await expect(page.getByLabel(/^Categoría/)).toHaveValue(`Sub 22 ${suffix}`);
    await submit().click();
    await expect(page.getByRole('heading', { level: 1, name: `Temporada ${YEARS.inactive}` })).toBeVisible();
    expect(await storedRows(athlete.id)).toHaveLength(1);
  });
});

test.describe('permissions, privacy and layout (real backend)', () => {
  test('anonymous and family requests to load data are rejected and create nothing', async ({ browser, request }) => {
    const athlete = await seed.createAthleteWith(person('Vera'));
    const body = { temporadaId: seasons.inactive, categoria: `Intruso ${suffix}` };
    expect((await request.post(`/api/admin/deportistas/${athlete.id}/temporadas`, { data: body, headers: await csrfHeaders(request) })).status()).toBe(401);

    const familyContext = await browser.newContext({ baseURL: 'http://127.0.0.1:5173' });
    const familyPage = await familyContext.newPage();
    await familyPage.goto('/login');
    await familyPage.getByLabel(/^Correo electrónico/).fill(intruder.email);
    await familyPage.getByLabel(/^Contraseña/).fill(intruder.password);
    await familyPage.getByRole('button', { name: 'Ingresar' }).click();
    await expect(familyPage).toHaveURL(/\/familia$/);
    expect((await familyContext.request.post(`/api/admin/deportistas/${athlete.id}/temporadas`, { data: body, headers: await csrfHeaders(familyContext.request) })).status()).toBe(403);
    await familyPage.goto(loadPath(athlete.id));
    await expect(familyPage.getByRole('heading', { name: 'No tenés permiso para ver esta sección' })).toBeVisible();
    await familyContext.close();

    // The administrator without the CSRF header.
    expect((await adminContext.request.post(`/api/admin/deportistas/${athlete.id}/temporadas`, { data: body })).status()).toBe(403);
    expect(await storedRows(athlete.id)).toHaveLength(0);
  });

  test('the form fits a 360 px phone without horizontal scrolling, even with very long values', async () => {
    const athlete = await seed.createAthleteWith({ nombre: 'Nombre bastante largo para forzar el ajuste de linea', apellido: `Apellido igualmente extenso para pantallas chicas ${suffix}`, dni: syntheticDni() });
    await page.setViewportSize({ width: 360, height: 740 });
    try {
      await openLoadForm(athlete.id);
      await seasonSelect().selectOption({ label: `Temporada ${YEARS.inactive}` });
      await page.getByLabel(/^Categoría/).fill(`C${'x'.repeat(78)}`);
      await page.getByLabel(/^Divisional/).fill(`D${'y'.repeat(78)}`);
      await page.getByLabel(/^Número de corredor/).fill('N'.repeat(30));
      expect(await noHorizontalOverflow()).toBe(true);
      await submit().click();
      await expect(page.getByRole('heading', { level: 1, name: `Temporada ${YEARS.inactive}` })).toBeVisible();
      expect(await noHorizontalOverflow()).toBe(true);
    } finally {
      await page.setViewportSize({ width: 1280, height: 720 });
    }
  });

  test('writes no personal data and no draft to the address, the console or the browser storage', async () => {
    for (const value of [main.nombre, main.apellido, main.dni, `Sub 14 ${suffix}`, numberOf('007'), `Sub 20 ${suffix}`]) {
      expect(visitedUrls.join(' '), value).not.toContain(value);
      expect(visitedUrls.join(' '), value).not.toContain(encodeURIComponent(value));
      expect(consoleLines.join('\n'), value).not.toContain(value);
    }
    const stored = await page.evaluate(() => JSON.stringify([{ ...localStorage }, { ...sessionStorage }]));
    for (const value of [main.nombre, main.apellido, main.dni, `Sub 14 ${suffix}`, numberOf('007')]) expect(stored, value).not.toContain(value);
    expect(apiRequests.filter((request) => request.startsWith('POST ')).every((request) => /\/api\/admin\/deportistas\/[^/]+\/temporadas$|\/api\/admin\/deportistas$|\/api\/auth\//.test(request))).toBe(true);
  });
});
