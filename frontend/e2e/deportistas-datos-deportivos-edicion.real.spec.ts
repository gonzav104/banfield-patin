import { randomBytes } from 'node:crypto';
import { expect, test, type BrowserContext, type Page } from '@playwright/test';
import { AdminSeed, csrfHeaders, loginAdminUi, syntheticDni, type SeededFamily, type SportsRow } from './support/seed';

/**
 * REAL flows of the administrative EDITION of sports data (FE-05 / U10-C): browser -> Vite proxy -> Spring backend -> PostgreSQL, against
 * the contract of GET/PUT /api/admin/deportistas/{id}/temporadas/{temporadaId} and the per-row revision of U9. Requires the administrator
 * enrolled by auth.real.spec.ts (secret in e2e/.state.json). Everything is synthetic and created through the real admin API with the
 * helpers the seed already has (nothing was added to it; the second writer of the conflict tests is the same administrator writing
 * through the real API with a newer revision, which for the backend is equivalent). School isolation between two schools is covered by
 * the backend DB tests (a second school cannot be created through the admin API).
 *
 * The administrator signs in ONCE for the whole file (one sign-in costs one TOTP window). No answer is faked: the pending request is a
 * real PUT held by page.route, and the uncertain-answer tests cut the connection while the REAL backend does (or does not) apply the
 * request. No traces, screenshots or videos are recorded (see playwright.config.ts): names, categories and runner numbers never reach
 * artifacts.
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

/** Seasons no other spec uses; they are only created, never activated (an inactive season is still editable). */
const YEARS = { older: 2051, newer: 2052 } as const;
let seasons = { older: '', newer: '' };
const DRAFTS = { category: `Sub 16 ${suffix}`, branch: `Mixta ${suffix}` };

const person = (label: string) => ({ nombre: `${label} ${suffix}`, apellido: `Edicion ${suffix}`, dni: syntheticDni() });
const rowPath = (athleteId: string, seasonId: string) => `/admin/deportistas/${athleteId}/temporadas/${seasonId}`;
const editPath = (athleteId: string, seasonId: string) => `${rowPath(athleteId, seasonId)}/editar`;
const apiRow = (athleteId: string, seasonId: string) => `/api/admin/deportistas/${athleteId}/temporadas/${seasonId}`;
const puts = () => apiRequests.filter((request) => request.startsWith('PUT '));
const noHorizontalOverflow = () => page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth);
const save = () => page.getByRole('button', { name: /^(Guardar cambios|Guardando…)$/ });

interface StoredRow {
  categoria: string | null;
  rama: string | null;
  divisional: string | null;
  numeroCorredor: string | null;
  estadoDeportivo: string;
  version: string;
}

/** What the backend really has for one row, through the admin API. */
async function stored(athleteId: string, seasonId: string): Promise<StoredRow> {
  const response = await adminContext.request.get(apiRow(athleteId, seasonId));
  expect(response.ok()).toBe(true);
  return (await response.json()) as StoredRow;
}

/** The SECOND WRITER: the same administrator, through the real API, with the revision it just read. */
async function secondWriter(athleteId: string, seasonId: string, change: Partial<Pick<StoredRow, 'categoria' | 'rama' | 'divisional' | 'numeroCorredor' | 'estadoDeportivo'>>) {
  const current = await stored(athleteId, seasonId);
  const response = await adminContext.request.put(apiRow(athleteId, seasonId), {
    data: {
      categoria: current.categoria,
      rama: current.rama,
      divisional: current.divisional,
      numeroCorredor: current.numeroCorredor,
      estadoDeportivo: current.estadoDeportivo,
      ...change,
      versionEsperada: current.version,
    },
    headers: await csrfHeaders(adminContext.request),
  });
  expect(response.status(), 'second writer').toBe(200);
}

/** A new athlete with one row, so no scenario depends on what another one left behind. */
async function newRow(label: string, season: 'older' | 'newer', row: SportsRow, options: { inactiveAthlete?: boolean } = {}) {
  const athlete = await seed.createAthleteWith(person(label));
  await seed.addSportsRow(athlete.id, seasons[season], row);
  if (options.inactiveAthlete) await seed.deactivate(athlete.id);
  return { athleteId: athlete.id, seasonId: seasons[season] };
}

async function openEdit(athleteId: string, seasonId: string) {
  await page.goto(editPath(athleteId, seasonId));
  await expect(page.getByRole('form', { name: 'Editar datos deportivos' })).toBeVisible();
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
  seasons = { older: (await seed.season(YEARS.older)).id, newer: (await seed.season(YEARS.newer)).id };

  const anonymous = await browser.newContext({ baseURL: 'http://127.0.0.1:5173' });
  intruder = await seed.createFamilyWithUser(anonymous.request, `${base} usuaria`, {
    nombre: 'Ines',
    apellido: 'Intrusa',
    email: `edicion.${suffix}@example.test`,
    password: `Fam-${randomBytes(8).toString('hex')}`,
  });
  await anonymous.close();
});

test.afterAll(async () => {
  await adminContext.close();
});

test.describe('a confirmed edit (real backend)', () => {
  test('is reached from the season detail, replaces EVERYTHING (an emptied optional becomes null), keeps the leading zeros and moves the revision once', async () => {
    const number = `007${suffix}`;
    const { athleteId, seasonId } = await newRow('Lucia', 'older', { categoria: `Sub 14 ${suffix}`, rama: 'Femenino', divisional: 'A', numeroCorredor: number, estadoDeportivo: 'SUSPENDIDO' });
    const before = await stored(athleteId, seasonId);

    await page.goto(rowPath(athleteId, seasonId));
    await page.getByRole('link', { name: 'Editar datos deportivos' }).click();
    await expect(page).toHaveURL(new RegExp(`${editPath(athleteId, seasonId)}$`));
    await expect(page.getByLabel(/^Categoría/)).toHaveValue(`Sub 14 ${suffix}`);
    await expect(page.getByLabel(/^Número de corredor/)).toHaveValue(number);
    await expect(page.getByText('Estado en la escuela:').locator('..')).toContainText('Activo');

    await page.getByLabel(/^Categoría/).fill(`  Sub   16 ${suffix} `);
    await page.getByLabel(/^Rama/).fill('   ');
    await page.getByLabel(/^Estado deportivo de 2051/).selectOption('ACTIVO');
    await expect(page.getByText(/Se van a quitar estos datos guardados: Rama/)).toBeVisible();
    await save().click();

    await expect(page).toHaveURL(new RegExp(`${rowPath(athleteId, seasonId)}$`));
    await expect(page.getByText('Cambios guardados.')).toBeVisible();
    const after = await stored(athleteId, seasonId);
    expect(after).toMatchObject({ categoria: `Sub 16 ${suffix}`, rama: null, divisional: 'A', numeroCorredor: number, estadoDeportivo: 'ACTIVO' });
    expect(Number(after.version)).toBe(Number(before.version) + 1);
  });

  test('edits a row of an INACTIVE season of an INACTIVE athlete (the backend allows it) and shows the school state apart from the sports state', async () => {
    const { athleteId, seasonId } = await newRow('Ivan', 'older', { categoria: `Sub 10 ${suffix}`, estadoDeportivo: 'ACTIVO' }, { inactiveAthlete: true });

    await openEdit(athleteId, seasonId);
    await expect(page.getByText('Estado en la escuela:').locator('..')).toContainText('Inactivo');
    await expect(page.getByLabel(/^Estado deportivo de 2051/)).toHaveValue('ACTIVO');
    await page.getByLabel(/^Divisional/).fill('Z');
    await save().click();

    await expect(page.getByText('Cambios guardados.')).toBeVisible();
    expect((await stored(athleteId, seasonId)).divisional).toBe('Z');
  });

  test('with nothing changed there is nothing to save: the button is off, no PUT is sent and the revision does not move', async () => {
    const { athleteId, seasonId } = await newRow('Nora', 'newer', { categoria: `Sub 12 ${suffix}` });
    const before = await stored(athleteId, seasonId);
    const putsBefore = puts().length;

    await openEdit(athleteId, seasonId);
    await expect(save()).toBeDisabled();
    await page.getByLabel(/^Categoría/).fill(` Sub   12 ${suffix} `); // only what the normalisation removes
    await expect(save()).toBeDisabled();
    await expect(page.getByText('No hay cambios para guardar.')).toBeVisible();

    expect(puts().length).toBe(putsBefore);
    expect((await stored(athleteId, seasonId)).version).toBe(before.version);
  });

  test('a runner number taken by another athlete in that season is explained next to the number, the draft stays and another number goes through', async () => {
    const taken = `T${suffix}`;
    const owner = await newRow('Dueño', 'newer', { numeroCorredor: taken });
    const { athleteId, seasonId } = await newRow('Rita', 'newer', { categoria: `Sub 18 ${suffix}`, numeroCorredor: `U${suffix}` });
    expect(owner.seasonId).toBe(seasonId);
    const before = await stored(athleteId, seasonId);

    await openEdit(athleteId, seasonId);
    await page.getByLabel(/^Número de corredor/).fill(taken);
    await save().click();

    await expect(page.getByText('Ese número de corredor ya lo tiene otro deportista en esa temporada. Elegí otro o dejalo vacío.')).toBeVisible();
    await expect(page.getByLabel(/^Número de corredor/)).toHaveAttribute('aria-invalid', 'true');
    await expect(page.getByLabel(/^Número de corredor/)).toHaveValue(taken);
    expect((await stored(athleteId, seasonId)).version).toBe(before.version);

    await page.getByLabel(/^Número de corredor/).fill(`V${suffix}`);
    await save().click();
    await expect(page.getByText('Cambios guardados.')).toBeVisible();
    expect((await stored(athleteId, seasonId)).numeroCorredor).toBe(`V${suffix}`);
  });
});

test.describe('a second writer (real backend)', () => {
  test('a stale revision is refused, nothing is lost and the administrator resolves FIELD BY FIELD before a new attempt', async () => {
    const { athleteId, seasonId } = await newRow('Eva', 'older', { categoria: `Sub 14 ${suffix}`, rama: 'Femenino', divisional: 'A', estadoDeportivo: 'ACTIVO' });

    await openEdit(athleteId, seasonId);
    await page.getByLabel(/^Categoría/).fill(DRAFTS.category);
    await page.getByLabel(/^Rama/).fill(DRAFTS.branch);
    await secondWriter(athleteId, seasonId, { categoria: `Sub 18 ${suffix}`, divisional: 'B' }); // another administrator, after this form opened
    const putsBefore = puts().length;
    await save().click();

    await expect(page.getByText(/Otra persona pudo haber modificado estos datos mientras los editabas\./)).toBeVisible();
    await expect(page.getByLabel(/^Categoría/)).toHaveValue(DRAFTS.category);
    await expect(page.getByLabel(/^Categoría/)).toBeDisabled();
    await expect(save()).toBeDisabled();
    await page.waitForTimeout(500);
    expect(puts().length - putsBefore).toBe(1); // no resend by itself
    expect((await stored(athleteId, seasonId)).divisional).toBe('B'); // the other writer's data is intact

    await page.getByRole('button', { name: 'Ver datos vigentes' }).click();
    const group = page.getByRole('group', { name: 'Datos vigentes' });
    const table = group.getByRole('table', { name: 'Comparación de datos' });
    await expect(table.getByRole('rowheader', { name: /^Categoría/ })).toContainText('Conflicto: los dos lo cambiaron distinto');
    await expect(table.getByRole('rowheader', { name: /^Rama/ })).toContainText('Solo cambiaste vos: se conserva tu valor');
    await expect(table.getByRole('rowheader', { name: /^Divisional/ })).toContainText('Cambió otra persona: se incorpora el valor vigente');
    await expect(group.getByRole('radio', { checked: true })).toHaveCount(0); // no default
    await expect(group.getByRole('button', { name: 'Seguir editando con los datos vigentes' })).toBeDisabled();

    await group.getByRole('group', { name: 'Categoría' }).getByRole('radio', { name: /Mantener mi valor/ }).check();
    await group.getByRole('button', { name: 'Seguir editando con los datos vigentes' }).click();
    await expect(page.getByLabel(/^Categoría/)).toHaveValue(DRAFTS.category);
    await expect(page.getByLabel(/^Rama/)).toHaveValue(DRAFTS.branch);
    await expect(page.getByLabel(/^Divisional/)).toHaveValue('B');
    await expect(save()).toBeEnabled();
    expect(puts().length - putsBefore).toBe(1); // deciding sends nothing
    await save().click();

    await expect(page.getByText('Cambios guardados.')).toBeVisible();
    expect(await stored(athleteId, seasonId)).toMatchObject({ categoria: DRAFTS.category, rama: DRAFTS.branch, divisional: 'B' });
  });

  test('a stale revision is refused EVEN when the other writer saved exactly the same values (the real contract), and the screen says so', async () => {
    const { athleteId, seasonId } = await newRow('Sofia', 'newer', { categoria: `Sub 14 ${suffix}` });

    await openEdit(athleteId, seasonId);
    const oldVersion = (await stored(athleteId, seasonId)).version;
    await page.getByLabel(/^Categoría/).fill(DRAFTS.category);
    await secondWriter(athleteId, seasonId, { categoria: DRAFTS.category });
    await save().click();
    await expect(page.getByText(/Otra persona pudo haber modificado estos datos/)).toBeVisible();

    // The contract itself, without the screen: the revision is what is compared, not the values.
    const direct = await adminContext.request.put(apiRow(athleteId, seasonId), {
      data: { categoria: DRAFTS.category, rama: null, divisional: null, numeroCorredor: null, estadoDeportivo: 'ACTIVO', versionEsperada: oldVersion },
      headers: await csrfHeaders(adminContext.request),
    });
    expect(direct.status()).toBe(409);
    expect(((await direct.json()) as { codigo: string }).codigo).toBe('EDICION_DESACTUALIZADA');

    await page.getByRole('button', { name: 'Ver datos vigentes' }).click();
    await expect(page.getByText(/coinciden con tu borrador, pero no podemos saber si los guardó tu envío u otro cambio/)).toBeVisible();
  });
});

test.describe('a request that is still on its way and answers that cannot be confirmed (real backend)', () => {
  test('a pending PUT locks every field and the button, and three quick clicks send ONE request', async () => {
    const { athleteId, seasonId } = await newRow('Tomas', 'older', { categoria: `Sub 20 ${suffix}` });
    await openEdit(athleteId, seasonId);
    await page.getByLabel(/^Categoría/).fill(DRAFTS.category);
    let release: () => void = () => undefined;
    const gate = new Promise<void>((resolve) => {
      release = resolve;
    });
    await page.route(`**${apiRow(athleteId, seasonId)}`, async (route) => {
      if (route.request().method() !== 'PUT') return route.continue();
      await gate;
      return route.continue();
    });
    const putsBefore = puts().length;
    try {
      await save().click({ clickCount: 3 });
      await expect(save()).toHaveText('Guardando…');
      await expect(save()).toBeDisabled();
      for (const label of [/^Categoría/, /^Rama/, /^Divisional/, /^Número de corredor/, /^Estado deportivo de 2051/]) await expect(page.getByLabel(label)).toBeDisabled();
      release();
      await expect(page.getByText('Cambios guardados.')).toBeVisible();
    } finally {
      release();
      await page.unroute(`**${apiRow(athleteId, seasonId)}`);
    }
    expect(puts().length - putsBefore).toBe(1);
    expect((await stored(athleteId, seasonId)).categoria).toBe(DRAFTS.category);
  });

  test('a connection cut AFTER the backend applied the PUT: nothing is claimed, and the reading shows the observed state WITHOUT attributing it to the request', async () => {
    const { athleteId, seasonId } = await newRow('Uma', 'newer', { categoria: `Sub 20 ${suffix}`, rama: 'Femenino' });
    await openEdit(athleteId, seasonId);
    const before = await stored(athleteId, seasonId);
    await page.getByLabel(/^Categoría/).fill(DRAFTS.category);
    await page.getByLabel(/^Rama/).fill('');
    await page.route(`**${apiRow(athleteId, seasonId)}`, async (route) => {
      if (route.request().method() !== 'PUT') return route.continue();
      await route.fetch(); // the REAL backend applies it...
      return route.abort('failed'); // ...and the browser never sees the answer
    });
    const putsBefore = puts().length;
    try {
      await save().click();
      await expect(page.getByText('No pudimos confirmar si el cambio se guardó.')).toBeVisible();
    } finally {
      await page.unroute(`**${apiRow(athleteId, seasonId)}`);
    }
    await expect(page.getByLabel(/^Categoría/)).toBeDisabled();
    await expect(page.getByLabel(/^Categoría/)).toHaveValue(DRAFTS.category);
    await expect(page.getByText('Cambios guardados.')).toHaveCount(0);
    await page.waitForTimeout(500);
    expect(puts().length - putsBefore).toBe(1); // no resend

    await page.getByRole('button', { name: 'Verificar datos vigentes' }).click();
    const group = page.getByRole('group', { name: 'Verificación' });
    await expect(group.getByText('Los datos vigentes coinciden con tu borrador, pero no podemos saber si los guardó tu envío u otro cambio. No hace falta volver a guardar.')).toBeVisible();
    await expect(group.getByRole('button', { name: 'Volver a intentar' })).toHaveCount(0);
    const after = await stored(athleteId, seasonId);
    expect(after).toMatchObject({ categoria: DRAFTS.category, rama: null });
    expect(Number(after.version)).toBe(Number(before.version) + 1); // it was applied exactly once
  });

  test('a connection cut BEFORE the request reached the backend: the revision did not move, the screen does not rule out an application, and only an explicit retry sends again with the SAME revision', async () => {
    const { athleteId, seasonId } = await newRow('Vera', 'older', { categoria: `Sub 22 ${suffix}` });
    await openEdit(athleteId, seasonId);
    const before = await stored(athleteId, seasonId);
    await page.getByLabel(/^Categoría/).fill(DRAFTS.category);
    await page.route(`**${apiRow(athleteId, seasonId)}`, (route) => (route.request().method() === 'PUT' ? route.abort('failed') : route.continue()), { times: 1 });
    await save().click();
    await expect(page.getByText('No pudimos confirmar si el cambio se guardó.')).toBeVisible();
    const sentOnce = puts().length;

    await page.getByRole('button', { name: 'Verificar datos vigentes' }).click();
    const group = page.getByRole('group', { name: 'Verificación' });
    await expect(group.getByText(/La revisión no cambió: no vemos cambios guardados\./)).toBeVisible();
    await expect(group.getByText(/no descarta del todo que el envío se haya aplicado/)).toBeVisible();
    expect(puts().length).toBe(sentOnce);
    expect((await stored(athleteId, seasonId)).categoria).toBe(`Sub 22 ${suffix}`);

    await group.getByRole('button', { name: 'Volver a intentar' }).click();
    await expect(page.getByLabel(/^Categoría/)).toHaveValue(DRAFTS.category);
    expect(puts().length).toBe(sentOnce); // opening the form is not sending it
    await save().click();
    await expect(page.getByText('Cambios guardados.')).toBeVisible();
    const after = await stored(athleteId, seasonId);
    expect(after.categoria).toBe(DRAFTS.category);
    expect(Number(after.version)).toBe(Number(before.version) + 1);
  });
});

test.describe('navigation, permissions, privacy and layout (real backend)', () => {
  test('leaving with unsaved changes asks first, and leaves without writing anything when the administrator decides to', async () => {
    const { athleteId, seasonId } = await newRow('Wanda', 'newer', { categoria: `Sub 14 ${suffix}` });
    const before = await stored(athleteId, seasonId);
    const putsBefore = puts().length;

    await openEdit(athleteId, seasonId);
    await page.getByLabel(/^Categoría/).fill(DRAFTS.category);
    await page.getByRole('link', { name: 'Cancelar' }).click();
    await expect(page.getByRole('button', { name: 'Descartar y salir' })).toBeVisible();
    await expect(page).toHaveURL(new RegExp(`${editPath(athleteId, seasonId)}$`));
    await page.getByRole('button', { name: 'Descartar y salir' }).click();

    await expect(page).toHaveURL(new RegExp(`${rowPath(athleteId, seasonId)}$`));
    expect(puts().length).toBe(putsBefore);
    expect((await stored(athleteId, seasonId)).version).toBe(before.version);
  });

  test('anonymous and family requests to edit are rejected and change nothing', async ({ browser, request }) => {
    const { athleteId, seasonId } = await newRow('Xena', 'older', { categoria: `Sub 14 ${suffix}` });
    const before = await stored(athleteId, seasonId);
    const body = { categoria: `Intruso ${suffix}`, rama: null, divisional: null, numeroCorredor: null, estadoDeportivo: 'ACTIVO', versionEsperada: before.version };
    expect((await request.put(apiRow(athleteId, seasonId), { data: body, headers: await csrfHeaders(request) })).status()).toBe(401);

    const familyContext = await browser.newContext({ baseURL: 'http://127.0.0.1:5173' });
    const familyPage = await familyContext.newPage();
    await familyPage.goto('/login');
    await familyPage.getByLabel(/^Correo electrónico/).fill(intruder.email);
    await familyPage.getByLabel(/^Contraseña/).fill(intruder.password);
    await familyPage.getByRole('button', { name: 'Ingresar' }).click();
    await expect(familyPage).toHaveURL(/\/familia$/);
    expect((await familyContext.request.put(apiRow(athleteId, seasonId), { data: body, headers: await csrfHeaders(familyContext.request) })).status()).toBe(403);
    await familyPage.goto(editPath(athleteId, seasonId));
    await expect(familyPage.getByRole('heading', { name: 'No tenés permiso para ver esta sección' })).toBeVisible();
    await familyContext.close();

    // The administrator without the CSRF header.
    expect((await adminContext.request.put(apiRow(athleteId, seasonId), { data: body })).status()).toBe(403);
    expect(await stored(athleteId, seasonId)).toMatchObject({ categoria: `Sub 14 ${suffix}`, version: before.version });
  });

  test('the form fits a 360 px phone without horizontal scrolling, even with very long values', async () => {
    const { athleteId, seasonId } = await newRow('Yara', 'newer', { categoria: `C${'x'.repeat(78)}`, rama: 'R'.repeat(40), divisional: `D${'y'.repeat(78)}`, numeroCorredor: 'N'.repeat(30) });
    await page.setViewportSize({ width: 360, height: 740 });
    try {
      await openEdit(athleteId, seasonId);
      expect(await noHorizontalOverflow()).toBe(true);
      await page.getByLabel(/^Divisional/).fill(`E${'z'.repeat(78)}`);
      await save().click();
      await expect(page.getByRole('heading', { level: 1, name: `Temporada ${YEARS.newer}` })).toBeVisible();
      expect(await noHorizontalOverflow()).toBe(true);
    } finally {
      await page.setViewportSize({ width: 1280, height: 720 });
    }
  });

  test('writes no personal data and no draft to the address, the console or the browser storage', async () => {
    for (const value of [DRAFTS.category, DRAFTS.branch, `Sub 14 ${suffix}`, `Edicion ${suffix}`]) {
      expect(visitedUrls.join(' '), value).not.toContain(value);
      expect(visitedUrls.join(' '), value).not.toContain(encodeURIComponent(value));
      expect(consoleLines.join('\n'), value).not.toContain(value);
    }
    const stored = await page.evaluate(() => JSON.stringify([{ ...localStorage }, { ...sessionStorage }]));
    for (const value of [DRAFTS.category, DRAFTS.branch, `Sub 14 ${suffix}`, `Edicion ${suffix}`]) expect(stored, value).not.toContain(value);
    expect(apiRequests.filter((request) => request.startsWith('PUT ')).every((request) => /^PUT \/api\/admin\/deportistas\/[^/]+\/temporadas\/[^/]+$/.test(request))).toBe(true);
  });
});
