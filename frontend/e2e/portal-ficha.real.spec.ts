import { randomBytes, randomUUID } from 'node:crypto';
import { expect, test, type Browser, type BrowserContext, type Page } from '@playwright/test';
import { athleteFichaSchema } from '../src/features/familia/schemas';
import { dbQuery, hasDisposableDb } from './support/db';
import { AdminSeed, birthDateForAge, dateForms, loginAdminUi, schoolToday, type SeededFamily } from './support/seed';

/**
 * REAL flows of the athlete sheet (FE-04): browser -> Vite proxy -> Spring backend -> PostgreSQL. Requires the
 * administrator enrolled by auth.real.spec.ts. All data is synthetic and is seeded through the real admin API (the only
 * SQL is the labelled "no active season" test). The tests that use page.route say SIMULATED in their title: the delay or
 * the outage is injected, but the answers come from the real server.
 *
 * Family A has eight athletes, each one a different case of the sheet; family B has one, which A must never reach.
 */
test.describe.configure({ mode: 'serial' });

const suffix = randomBytes(3).toString('hex');
const password = () => `Fam-${randomBytes(8).toString('hex')}`;
const YEAR = Number(schoolToday().slice(0, 4));
const AGE = 9;
const BIRTH_DATE = birthDateForAge(AGE);
const escapeRegex = (text: string) => text.replace(/[.*+?^${}()|[\]\\/-]/g, '\\$&');
/** The exact birth date in every format a page or a log could print it, plus the field names that must not travel. */
const SENSITIVE = (dnis: string[]) => new RegExp([...dateForms(BIRTH_DATE).map(escapeRegex), ...dnis, 'fechaNacimiento', 'cuil', 'domicilio', '"dni"'].join('|'), 'i');

/** One athlete per case of the sheet. */
const CASES = {
  complete: { name: 'E2E-Ficha-Completa', birth: true, active: true },
  suspended: { name: 'E2E-Ficha-Suspendido', birth: true, active: true },
  inactive: { name: 'E2E-Ficha-Inactivo', birth: true, active: false },
  noAge: { name: 'E2E-Ficha-SinEdad', birth: false, active: true },
  noSeason: { name: 'E2E-Ficha-SinTemporada', birth: true, active: true },
  nulls: { name: 'E2E-Ficha-Nulos', birth: true, active: true },
  revoked: { name: 'E2E-Ficha-Revocado', birth: true, active: true },
  foreign: { name: 'E2E-Ficha-Ajeno', birth: true, active: true }, // family B's
} as const;
type CaseKey = keyof typeof CASES;

let browserRef: Browser;
let adminContext: BrowserContext;
let seed: AdminSeed;
let familyA: SeededFamily;
let familyB: SeededFamily;
let currentSeasonId = '';
const ids = {} as Record<CaseKey, string>;
const dnis: string[] = [];
/** Every /api/familia response any family context received during this file, for the final privacy sweep. */
const portalResponses: { url: string; status: number; body: string }[] = [];

const sheetUrl = (key: CaseKey) => `/familia/deportistas/${ids[key]}`;
const fichaApi = (id: string) => `/api/familia/deportistas/${id}/ficha`;
const noHorizontalOverflow = (page: Page) => page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth);

async function newContext(): Promise<{ context: BrowserContext; page: Page }> {
  const context = await browserRef.newContext({ baseURL: 'http://127.0.0.1:5173' });
  context.on('response', (response) => {
    if (!response.url().includes('/api/familia/')) return;
    void response.text().then(
      (body) => portalResponses.push({ url: new URL(response.url()).pathname, status: response.status(), body }),
      () => undefined,
    );
  });
  return { context, page: await context.newPage() };
}

async function loginFamilyUi(page: Page, family: SeededFamily): Promise<void> {
  await page.goto('/login');
  await page.getByLabel(/^Correo electrónico/).fill(family.email);
  await page.getByLabel(/^Contraseña/).fill(family.password);
  await page.getByRole('button', { name: 'Ingresar' }).click();
  await expect(page).toHaveURL(/\/familia$/);
}

/** Signs in as `family` and lands on a sheet by typing its address (direct access). */
async function openSheet(page: Page, family: SeededFamily, key: CaseKey): Promise<void> {
  await loginFamilyUi(page, family);
  await page.goto(sheetUrl(key));
  await expect(page.getByRole('heading', { level: 1 })).toContainText(CASES[key].name);
}

const section = (page: Page, title: string | RegExp) => page.getByRole('region', { name: title });
const row = (scope: ReturnType<typeof section>, term: string) => scope.locator('dt', { hasText: new RegExp(`^${term}$`) }).locator('xpath=following-sibling::dd[1]');
const notFoundNotice = (page: Page) => page.getByText('No encontramos este deportista');

test.beforeAll(async ({ browser }) => {
  browserRef = browser;
  adminContext = await browser.newContext({ baseURL: 'http://127.0.0.1:5173' });
  await loginAdminUi(await adminContext.newPage());
  seed = new AdminSeed(adminContext.request);
  const anonymous = await browser.newContext({ baseURL: 'http://127.0.0.1:5173' });

  familyA = await seed.createFamilyWithUser(anonymous.request, `E2E Ficha Familia A ${suffix}`, { nombre: 'Lucía', apellido: 'Prueba', email: `ficha.a.${suffix}@example.test`, password: password() });
  familyB = await seed.createFamilyWithUser(anonymous.request, `E2E Ficha Familia B ${suffix}`, { nombre: 'Rosa', apellido: 'Prueba', email: `ficha.b.${suffix}@example.test`, password: password() });
  await anonymous.close();

  for (const key of Object.keys(CASES) as CaseKey[]) {
    const spec = CASES[key];
    const created = await seed.createAthlete({ nombre: spec.name, apellido: 'Sintetico', ...(spec.birth ? { fechaNacimiento: BIRTH_DATE } : {}) });
    ids[key] = created.id;
    dnis.push(created.dni);
  }
  await seed.link(familyA.familyId, (Object.keys(CASES) as CaseKey[]).filter((k) => k !== 'foreign').map((k) => ids[k]));
  await seed.link(familyB.familyId, [ids.foreign]);

  // The previous season holds DIFFERENT data for two athletes: it must never reach the screen. The current one is active.
  const previous = await seed.season(YEAR - 1);
  const current = await seed.season(YEAR);
  await seed.activateSeason(current.id);
  currentSeasonId = current.id;
  await seed.addSportsRow(ids.complete, previous.id, { categoria: 'Sub-8-Anterior', rama: 'Mixta-Anterior', divisional: 'B-Anterior', numeroCorredor: '042', estadoDeportivo: 'INACTIVO' });
  await seed.addSportsRow(ids.noSeason, previous.id, { categoria: 'Sub-8-Anterior', rama: 'Mixta-Anterior', divisional: 'B-Anterior', numeroCorredor: '043', estadoDeportivo: 'ACTIVO' });
  await seed.addSportsRow(ids.complete, current.id, { categoria: 'Sub-10', rama: 'Femenina', divisional: 'Primera A', numeroCorredor: '007', estadoDeportivo: 'ACTIVO' });
  await seed.addSportsRow(ids.suspended, current.id, { categoria: 'Sub-12', rama: 'Masculina', divisional: 'Segunda', numeroCorredor: '15', estadoDeportivo: 'SUSPENDIDO' });
  await seed.addSportsRow(ids.inactive, current.id, { categoria: 'Sub-10', rama: 'Femenina', divisional: 'Primera A', numeroCorredor: '021', estadoDeportivo: 'ACTIVO' });
  await seed.addSportsRow(ids.noAge, current.id, { categoria: 'Sub-10', rama: 'Femenina', divisional: 'Primera A', numeroCorredor: '033', estadoDeportivo: 'ACTIVO' });
  await seed.addSportsRow(ids.nulls, current.id, { estadoDeportivo: 'ACTIVO' }); // no category, branch, division or number
  await seed.addSportsRow(ids.foreign, current.id, { categoria: 'Sub-14', rama: 'Mixta', divisional: 'Tercera', numeroCorredor: '099', estadoDeportivo: 'ACTIVO' });
  // Last: an inactive athlete cannot be linked and a revoked link cannot receive new data in the same way, so both come after.
  await seed.deactivate(ids.inactive);
  await seed.revoke(familyA.familyId, ids.revoked);
});

test.afterAll(async () => {
  await adminContext?.close();
});

test.describe('flow of a family: list, sheet, back', () => {
  test('from the list to the sheet of the athlete, with the current season and the runner number as text', async () => {
    const { context, page } = await newContext();
    await loginFamilyUi(page, familyA);
    await page.getByRole('navigation', { name: 'Principal' }).getByRole('link', { name: 'Deportistas' }).click();
    await expect(page.getByText(/en total/)).toBeVisible();

    await page.getByRole('link', { name: new RegExp(CASES.complete.name) }).click();
    await expect(page).toHaveURL(new RegExp(`/familia/deportistas/${ids.complete}$`));
    const title = page.getByRole('heading', { level: 1 });
    await expect(title).toHaveText(`${CASES.complete.name} Sintetico`);
    await expect(title).toBeFocused(); // a new screen: the keyboard lands on its title

    const data = section(page, 'Datos del deportista');
    await expect(row(data, 'Edad')).toHaveText(`${AGE} años`);
    await expect(row(data, 'Estado en la escuela')).toHaveText('Activo');
    const season = section(page, `Temporada ${YEAR}`);
    await expect(row(season, 'Categoría')).toHaveText('Sub-10');
    await expect(row(season, 'Rama')).toHaveText('Femenina');
    await expect(row(season, 'Divisional')).toHaveText('Primera A');
    await expect(row(season, 'Número de corredor')).toHaveText('007'); // leading zeros kept: it is text, not 7
    await expect(row(season, 'Estado deportivo')).toHaveText('Activo');
    await expect(page.getByText(/Son dos cosas distintas/)).toBeVisible(); // the school state and the sports state are told apart
    expect(await page.locator('body').innerText()).not.toMatch(/Anterior/); // nothing of the previous season

    await page.getByRole('link', { name: 'Volver a deportistas' }).click();
    await expect(page).toHaveURL(/\/familia\/deportistas$/);
    await expect(page.getByText(/en total/)).toBeVisible();
    await context.close();
  });

  test('direct access by address and reload with a valid session; the browser history returns to the list', async () => {
    const { context, page } = await newContext();
    await openSheet(page, familyA, 'complete');

    await page.reload();
    await expect(page.getByRole('heading', { level: 1 })).toHaveText(`${CASES.complete.name} Sintetico`);
    await expect(row(section(page, `Temporada ${YEAR}`), 'Número de corredor')).toHaveText('007');

    await page.goto('/familia/deportistas');
    await page.getByRole('link', { name: new RegExp(CASES.suspended.name) }).click();
    await expect(page.getByRole('heading', { level: 1 })).toContainText(CASES.suspended.name);
    await page.goBack();
    await expect(page).toHaveURL(/\/familia\/deportistas$/);
    await page.goForward();
    await expect(page.getByRole('heading', { level: 1 })).toContainText(CASES.suspended.name);
    await context.close();
  });

  test('keyboard: the card is a link, Enter opens the sheet with focus on its title and the way back is reachable', async () => {
    const { context, page } = await newContext();
    await loginFamilyUi(page, familyA);
    await page.goto('/familia/deportistas');
    const card = page.getByRole('link', { name: new RegExp(CASES.complete.name) });
    await card.focus();
    await expect(card).toBeFocused();
    await page.keyboard.press('Enter');
    await expect(page.getByRole('heading', { level: 1 })).toBeFocused();
    expect(await page.getByRole('heading', { level: 1 }).count()).toBe(1); // exactly one title
    await expect(page.getByRole('heading', { level: 2 })).toHaveText(['Datos del deportista', `Temporada ${YEAR}`]);

    await page.keyboard.press('Shift+Tab'); // the way back sits right before the title in the reading order
    const back = page.getByRole('link', { name: 'Volver a deportistas' });
    await expect(back).toBeFocused();
    await page.keyboard.press('Enter');
    await expect(page).toHaveURL(/\/familia\/deportistas$/);
    await context.close();
  });
});

test.describe('the cases of the sheet (real data)', () => {
  test('SUSPENDIDO is shown as it is, with no invented cause', async () => {
    const { context, page } = await newContext();
    await openSheet(page, familyA, 'suspended');
    const season = section(page, `Temporada ${YEAR}`);
    await expect(row(season, 'Estado deportivo')).toHaveText('Suspendido');
    await expect(row(season, 'Número de corredor')).toHaveText('15');
    expect(await page.locator('main').innerText()).not.toMatch(/motivo|causa|sanci[oó]n|raz[oó]n|porque/i);
    await context.close();
  });

  test('an inactive athlete: «Estado en la escuela» Inactivo and «Estado deportivo» Activo are two different things', async () => {
    const { context, page } = await newContext();
    await openSheet(page, familyA, 'inactive');
    await expect(row(section(page, 'Datos del deportista'), 'Estado en la escuela')).toHaveText('Inactivo');
    await expect(row(section(page, `Temporada ${YEAR}`), 'Estado deportivo')).toHaveText('Activo');
    await context.close();
  });

  test('a null age is «Edad no informada», never 0 or a guess', async () => {
    const { context, page } = await newContext();
    await openSheet(page, familyA, 'noAge');
    await expect(row(section(page, 'Datos del deportista'), 'Edad')).toHaveText('Edad no informada');
    await expect(row(section(page, `Temporada ${YEAR}`), 'Número de corredor')).toHaveText('033');
    await context.close();
  });

  test('no row in the current season: neutral text, no cause and nothing from the previous season', async () => {
    const { context, page } = await newContext();
    await openSheet(page, familyA, 'noSeason');
    const season = section(page, 'Temporada vigente');
    await expect(season).toContainText('No hay datos de la temporada vigente para mostrar.');
    await expect(row(section(page, 'Datos del deportista'), 'Edad')).toHaveText(`${AGE} años`);
    const text = await page.locator('main').innerText();
    expect(text).not.toMatch(/Anterior|043|Sub-8/); // the previous season has data for this athlete: it is not the current one
    expect(text).not.toMatch(/motivo|causa|porque/i);
    await context.close();
  });

  test('null sports fields are «No informado» (four times) and the status still shows', async () => {
    const { context, page } = await newContext();
    await openSheet(page, familyA, 'nulls');
    const season = section(page, `Temporada ${YEAR}`);
    for (const term of ['Categoría', 'Rama', 'Divisional', 'Número de corredor']) await expect(row(season, term)).toHaveText('No informado');
    await expect(row(season, 'Estado deportivo')).toHaveText('Activo');
    await context.close();
  });

  test('with NO active season the sheet shows the neutral text for everyone (the only SQL of this file, on the disposable database)', async () => {
    test.skip(!hasDisposableDb(), 'needs E2E_DB_CONTAINER (frontend-e2e.sh sets it)');
    const { context, page } = await newContext();
    await loginFamilyUi(page, familyA);
    try {
      dbQuery('UPDATE gestion_patin.temporada SET activa = false');
      await page.goto(sheetUrl('complete'));
      await expect(page.getByRole('heading', { level: 1 })).toContainText(CASES.complete.name);
      await expect(section(page, 'Temporada vigente')).toContainText('No hay datos de la temporada vigente para mostrar.');
      const body = (await (await context.request.get(fichaApi(ids.complete))).json()) as { temporadaVigente: unknown };
      expect(body.temporadaVigente).toBeNull();
    } finally {
      await seed.activateSeason(currentSeasonId); // back to the seeded state for the tests that follow
    }
    await page.reload();
    await expect(section(page, `Temporada ${YEAR}`)).toBeVisible();
    await context.close();
  });
});

test.describe('the real contract and what never travels', () => {
  test('the sheet answers exactly the minimal fields and the Zod schema accepts every real answer', async () => {
    const { context, page } = await newContext();
    await loginFamilyUi(page, familyA);
    const api = context.request;
    for (const key of ['complete', 'suspended', 'inactive', 'noAge', 'noSeason', 'nulls'] as CaseKey[]) {
      const response = await api.get(fichaApi(ids[key]));
      expect(response.status(), key).toBe(200);
      const raw = await response.text();
      const body = JSON.parse(raw) as Record<string, unknown>;
      expect(Object.keys(body).sort(), key).toEqual(['activo', 'apellido', 'edad', 'nombre', 'temporadaVigente']);
      if (body.temporadaVigente !== null) expect(Object.keys(body.temporadaVigente as object).sort(), key).toEqual(['anio', 'categoria', 'divisional', 'estadoDeportivo', 'numeroCorredor', 'rama']);
      expect(raw, key).not.toMatch(SENSITIVE(dnis));
      // The real answer passes the strict schema of the screen and the schema removes nothing: no field was left unknown.
      const parsed = athleteFichaSchema.safeParse(body);
      expect(parsed.success, key).toBe(true);
      expect(parsed.success && parsed.data, key).toEqual(body);
    }
    const complete = (await (await api.get(fichaApi(ids.complete))).json()) as { edad: number; activo: boolean; temporadaVigente: { anio: number; numeroCorredor: string; estadoDeportivo: string } };
    expect(complete.edad).toBe(AGE);
    expect(complete.temporadaVigente).toMatchObject({ anio: YEAR, numeroCorredor: '007', estadoDeportivo: 'ACTIVO' }); // a JSON string: "007", not 7
    expect(((await (await api.get(fichaApi(ids.noAge))).json()) as { edad: unknown }).edad).toBeNull();
    expect(((await (await api.get(fichaApi(ids.inactive))).json()) as { activo: boolean }).activo).toBe(false);
    expect(((await (await api.get(fichaApi(ids.noSeason))).json()) as { temporadaVigente: unknown }).temporadaVigente).toBeNull();
    await context.close();
  });

  test('the family list carries the age and not the birth date, and the sheet screen shows no sensitive data', async () => {
    const { context, page } = await newContext();
    await loginFamilyUi(page, familyA);
    const listBodies: string[] = [];
    page.on('response', (response) => {
      if (new URL(response.url()).pathname === '/api/familia/deportistas') void response.text().then((text) => listBodies.push(text));
    });
    await page.goto('/familia/deportistas');
    await expect(page.getByText(/en total/)).toBeVisible();
    await page.getByRole('link', { name: new RegExp(CASES.complete.name) }).click();
    await expect(page.getByRole('heading', { level: 1 })).toContainText(CASES.complete.name);

    expect(listBodies.length).toBeGreaterThan(0);
    for (const raw of listBodies) {
      expect(raw).not.toMatch(SENSITIVE(dnis));
      const page0 = JSON.parse(raw) as { contenido: Record<string, unknown>[] };
      for (const athlete of page0.contenido) expect(Object.keys(athlete).sort()).toEqual(['activo', 'apellido', 'edad', 'id', 'nombre']);
    }
    const screen = await page.locator('body').innerText();
    expect(screen).not.toMatch(SENSITIVE(dnis));
    expect(await page.content()).not.toMatch(SENSITIVE(dnis)); // nor in the markup (attributes, hidden text)
    await context.close();
  });
});

test.describe('authorization against the real database', () => {
  test('a foreign athlete, a missing one and a revoked link answer the SAME 404, in the API and on the screen', async () => {
    const { context, page } = await newContext();
    await loginFamilyUi(page, familyA);
    const api = context.request;
    const answers = await Promise.all([ids.foreign, randomUUID(), ids.revoked].map((id) => api.get(fichaApi(id))));
    for (const answer of answers) expect(answer.status()).toBe(404);
    const bodies = await Promise.all(answers.map((answer) => answer.text()));
    expect(new Set(bodies).size).toBe(1); // byte for byte the same: nothing tells the three cases apart
    expect((JSON.parse(bodies[0]!) as { codigo: string }).codigo).toBe('DEPORTISTA_NO_ENCONTRADO');
    expect(bodies[0]).not.toContain(CASES.foreign.name);

    for (const id of [ids.foreign, randomUUID(), ids.revoked]) {
      await page.goto(`/familia/deportistas/${id}`);
      await expect(notFoundNotice(page)).toBeVisible();
      await expect(page.getByRole('link', { name: 'Volver a deportistas' })).toBeVisible();
      const text = await page.locator('main').innerText();
      expect(text).not.toContain('E2E-Ficha');
      expect(text).not.toContain(CASES.foreign.name);
    }
    await context.close();
  });

  test('a malformed id never reaches the server and shows the same notice', async () => {
    const { context, page } = await newContext();
    await loginFamilyUi(page, familyA);
    const sheetRequests: string[] = [];
    page.on('request', (request) => {
      if (new URL(request.url()).pathname.startsWith('/api/') && request.url().includes('/ficha')) sheetRequests.push(request.url()); // API calls only, not the dev server's ficha-state.ts
    });
    await page.goto('/familia/deportistas/no-es-un-uuid');
    await expect(notFoundNotice(page)).toBeVisible();
    expect(sheetRequests).toEqual([]);
    await context.close();
  });

  test('without a session: the API answers 401 and the address sends to the login with no data; the administrator is not a family', async () => {
    const { context, page } = await newContext();
    expect((await context.request.get(fichaApi(ids.complete))).status()).toBe(401);
    await page.goto(sheetUrl('complete'));
    await expect(page.getByRole('heading', { name: 'Ingresá a tu portal' })).toBeVisible();
    expect(await page.locator('body').innerText()).not.toContain('E2E-Ficha');

    expect((await adminContext.request.get(fichaApi(ids.complete))).status()).toBe(403); // ROLE_ADMIN is not ROLE_FAMILIA
    const admin = await adminContext.newPage();
    await admin.goto(sheetUrl('complete'));
    await expect(admin.getByRole('heading', { name: 'Datos del deportista' })).toHaveCount(0);
    expect(await admin.locator('body').innerText()).not.toContain('E2E-Ficha');
    await admin.close();
    await context.close();
  });
});

test.describe('sessions and identity changes', () => {
  test('an expired session on the sheet sends to login with no data and, after signing in again, returns to the same sheet', async () => {
    const { context, page } = await newContext();
    await openSheet(page, familyA, 'complete');

    await context.clearCookies(); // as far as the browser is concerned, the session is gone
    await page.reload();
    await expect(page.getByRole('heading', { name: 'Ingresá a tu portal' })).toBeVisible();
    expect(await page.locator('body').innerText()).not.toContain('E2E-Ficha');

    await page.getByLabel(/^Correo electrónico/).fill(familyA.email);
    await page.getByLabel(/^Contraseña/).fill(familyA.password);
    await page.getByRole('button', { name: 'Ingresar' }).click();
    await expect(page).toHaveURL(new RegExp(`/familia/deportistas/${ids.complete}$`)); // back where the user was
    await expect(page.getByRole('heading', { level: 1 })).toContainText(CASES.complete.name);
    await context.close();
  });

  test('a session lost while the sheet is on screen: the next request shows no data and asks to sign in', async () => {
    const { context, page } = await newContext();
    await openSheet(page, familyA, 'complete');
    await context.clearCookies();
    await page.getByRole('link', { name: 'Volver a deportistas' }).click(); // a new request: 401

    await expect(page.getByRole('heading', { name: 'Ingresá a tu portal' })).toBeVisible();
    expect(await page.locator('body').innerText()).not.toContain('E2E-Ficha');
    await context.close();
  });

  test('family A -> family B in the same browser: B cannot reach A\'s sheet, not even with history or the address', async () => {
    const { context, page } = await newContext();
    await openSheet(page, familyA, 'complete');
    await expect(row(section(page, `Temporada ${YEAR}`), 'Número de corredor')).toHaveText('007');

    await page.getByRole('button', { name: 'Cerrar sesión' }).click();
    await expect(page).toHaveURL(/\/login$/);
    expect(await page.locator('body').innerText()).not.toContain('E2E-Ficha');
    await loginFamilyUi(page, familyB);
    expect(await page.locator('body').innerText()).not.toContain(CASES.complete.name);

    const requests: { url: string; status: number }[] = [];
    page.on('response', (response) => {
      if (new URL(response.url()).pathname.startsWith('/api/') && response.url().includes('/ficha')) requests.push({ url: new URL(response.url()).pathname, status: response.status() });
    });
    await page.goBack(); // history still holds A's sheet and the login before it
    await page.goto(sheetUrl('complete'));
    await expect(notFoundNotice(page)).toBeVisible();
    const text = await page.locator('body').innerText();
    expect(text).not.toContain(CASES.complete.name);
    expect(text).not.toContain('007');
    // The answer came from the server for B (a 404), not from anything left in memory by A.
    expect(requests.some((r) => r.url === fichaApi(ids.complete) && r.status === 404)).toBe(true);

    await page.goto(sheetUrl('foreign')); // B's own athlete does open
    await expect(page.getByRole('heading', { level: 1 })).toContainText(CASES.foreign.name);
    await expect(row(section(page, `Temporada ${YEAR}`), 'Número de corredor')).toHaveText('099');
    await context.close();
  });

  test('SIMULATED slow answer (the real server answers late): leaving the sheet and changing the user never shows the late data', async () => {
    const { context, page } = await newContext();
    await loginFamilyUi(page, familyA);
    await page.route(`**${fichaApi(ids.complete)}`, async (route) => {
      await new Promise((resolve) => setTimeout(resolve, 2500));
      await route.continue().catch(() => undefined); // the app may have cancelled the request already: then there is nothing to continue
    });
    await page.goto(sheetUrl('complete'));
    await expect(page.getByText('Cargando la ficha…')).toBeVisible();
    expect(await page.locator('body').innerText()).not.toContain(CASES.complete.name); // nothing private while it loads

    // 1) Quick route change: the slow sheet is abandoned for another one, which must show only its own data.
    await page.getByRole('link', { name: 'Volver a deportistas' }).click();
    await page.getByRole('link', { name: new RegExp(CASES.suspended.name) }).click();
    await expect(page.getByRole('heading', { level: 1 })).toContainText(CASES.suspended.name);
    await page.waitForTimeout(3000); // the late answer of the first sheet has arrived by now
    expect(await page.locator('main').innerText()).not.toContain(CASES.complete.name);
    await expect(row(section(page, `Temporada ${YEAR}`), 'Estado deportivo')).toHaveText('Suspendido');

    // 2) Change of user while a sheet of A is still loading.
    await page.goto(sheetUrl('complete'));
    await expect(page.getByText('Cargando la ficha…')).toBeVisible();
    await page.getByRole('button', { name: 'Cerrar sesión' }).click();
    await expect(page).toHaveURL(/\/login$/);
    await page.unroute(`**${fichaApi(ids.complete)}`);
    await loginFamilyUi(page, familyB);
    await page.waitForTimeout(3000);
    expect(await page.locator('body').innerText()).not.toContain(CASES.complete.name);
    await context.close();
  });

  test('SIMULATED outage (page.route, not the real server): retryable error, session kept, then the sheet', async () => {
    const { context, page } = await newContext();
    await loginFamilyUi(page, familyA);
    await page.route(`**${fichaApi(ids.complete)}`, (route) => route.abort('connectionrefused'));
    await page.goto(sheetUrl('complete'));

    await expect(page.getByText(/No pudimos conectar con el servidor/)).toBeVisible();
    await expect(page.getByRole('button', { name: 'Cerrar sesión' })).toBeVisible(); // still signed in
    expect(await page.locator('main').innerText()).not.toContain(CASES.complete.name);

    await page.unroute(`**${fichaApi(ids.complete)}`);
    await page.getByRole('button', { name: 'Reintentar' }).click();
    await expect(page.getByRole('heading', { level: 1 })).toContainText(CASES.complete.name);
    await context.close();
  });
});

test.describe('mobile', () => {
  for (const width of [360, 390]) {
    test(`the family flow at ${width} px (list, full sheet, no season, not found) has no horizontal overflow`, async () => {
      const { context, page } = await newContext();
      await page.setViewportSize({ width, height: 800 });
      await loginFamilyUi(page, familyA);
      expect(await noHorizontalOverflow(page)).toBe(true);

      await page.getByRole('navigation', { name: 'Principal' }).getByRole('link', { name: 'Deportistas' }).click();
      await expect(page.getByText(/en total/)).toBeVisible();
      expect(await noHorizontalOverflow(page)).toBe(true);

      await page.getByRole('link', { name: new RegExp(CASES.complete.name) }).click();
      await expect(row(section(page, `Temporada ${YEAR}`), 'Número de corredor')).toHaveText('007');
      expect(await noHorizontalOverflow(page)).toBe(true);
      const back = page.getByRole('link', { name: 'Volver a deportistas' });
      expect((await back.boundingBox())!.height).toBeGreaterThanOrEqual(44); // a comfortable touch target

      for (const target of [sheetUrl('noSeason'), sheetUrl('nulls'), `/familia/deportistas/${randomUUID()}`]) {
        await page.goto(target);
        await expect(page.getByRole('heading', { level: 1 })).toBeVisible();
        await expect(page.getByText('Cargando la ficha…')).toHaveCount(0);
        expect(await noHorizontalOverflow(page)).toBe(true);
      }
      await context.close();
    });
  }
});

test('privacy sweep: no portal answer of this whole file carried the birth date, a DNI, a CUIL or an address', () => {
  expect(portalResponses.length).toBeGreaterThan(20);
  expect(portalResponses.filter((r) => r.url.endsWith('/ficha')).length).toBeGreaterThan(10);
  for (const response of portalResponses) expect(response.body, `${response.status} ${response.url}`).not.toMatch(SENSITIVE(dnis));
  // Every not-found answer of the portal is the same one.
  const notFound = portalResponses.filter((r) => r.status === 404).map((r) => r.body);
  expect(notFound.length).toBeGreaterThan(3);
  expect(new Set(notFound).size).toBe(1);
});
