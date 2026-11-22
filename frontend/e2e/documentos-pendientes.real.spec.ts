import { randomBytes, randomUUID } from 'node:crypto';
import { expect, test, type APIResponse, type Browser, type BrowserContext, type Locator, type Page } from '@playwright/test';
import { dbQuery, hasDisposableDb } from './support/db';
import { syntheticPdf } from './support/documents';
import { AdminSeed, csrfHeaders, loginAdminUi, type SeededFamily } from './support/seed';

/**
 * REAL pending-documents inbox (administrative «Documentos pendientes»): browser -> Vite proxy -> Spring backend -> PostgreSQL, with the document pipeline
 * ENABLED against the Storage and clamd test doubles started by frontend-e2e.sh. The family uploads through the real API (as in the
 * U16 spec); the administrator finds the documents in the inbox, goes to each ficha through its link, reviews there, and sees the
 * inbox change: approved and observed documents leave it and the replacement of an observed one comes back marked as such.
 *
 * The school is shared with the other specs of the shard, so this file never counts the whole inbox: it looks for ITS rows, by the
 * unique names it created, through the real filter and pagination of the screen.
 */
test.describe.configure({ mode: 'serial' });

const OBJECTS_DIR = process.env.E2E_STORAGE_OBJECTS_DIR ?? '';
test.skip(OBJECTS_DIR === '' && !process.env.CI, 'Needs .github/scripts/frontend-e2e.sh: the document pipeline enabled against the Storage and clamd doubles.');

const suffix = randomBytes(3).toString('hex');
const FAMILY_NAME = `E2E Pendientes Familia ${suffix}`;
const ATHLETE = `E2E-Pend-Dep-${suffix}`;
const TUTOR = `E2E-Pend-Tut-${suffix}`;
const ATHLETE_FULL = `${ATHLETE} Sintetico`;
const TUTOR_FULL = `${TUTOR} Sintetico`;
const FRONT = 'DNI – frente';
const BACK = 'DNI – dorso';
const TUTOR_FRONT = 'DNI del tutor – frente';
const REPLACEMENT = 'Reemplazo de un documento observado';

let browserRef: Browser;
let adminContext: BrowserContext;
let admin: Page;
let familyContext: BrowserContext;
let family: SeededFamily;
let athleteId = '';
let tutorId = '';
const adminConsole: string[] = [];

const results = (page: Page) => page.getByRole('region', { name: 'Resultados' });
/** The count line or the empty notice: the list has finished loading. */
const settled = (page: Page) => results(page).getByRole('status').filter({ hasNotText: /Cargando/ });
const documentsSection = (page: Page) => page.getByRole('region', { name: 'Documentos', exact: true });
const ficha = (page: Page, label: string) => documentsSection(page).getByRole('listitem').filter({ has: page.getByRole('heading', { name: label, exact: true }) });

/**
 * Walks the inbox of ONE owner kind through the screen's own filter and pagination (the school is shared with other specs, and
 * this file's rows can straddle a page boundary), calling `visit` with every row on every page until it returns something.
 */
async function walkInbox<T>(page: Page, owner: 'DEPORTISTA' | 'TUTOR', visit: (rows: Locator) => Promise<T | undefined>): Promise<T[]> {
  await page.goto(`/admin/documentos/pendientes?titular=${owner}`);
  await expect(page.getByRole('heading', { level: 1, name: 'Documentos pendientes' })).toBeVisible();
  const out: T[] = [];
  for (let guard = 0; guard < 100; guard += 1) {
    await expect(settled(page)).toBeVisible();
    const found = await visit(results(page).getByRole('listitem'));
    if (found !== undefined) {
      out.push(found);
      return out;
    }
    const next = page.getByRole('button', { name: 'Siguiente' });
    if (!(await next.isVisible()) || (await next.isDisabled())) return out;
    const before = await results(page).getByRole('listitem').first().innerText();
    await next.click();
    await expect(results(page).getByRole('listitem').first()).not.toHaveText(before);
  }
  throw new Error('the inbox has more pages than any E2E school can hold');
}

/** The texts of every row of one owner that carries `fullName`, across all pages (empty when it has none). */
async function rowTexts(page: Page, owner: 'DEPORTISTA' | 'TUTOR', fullName: string): Promise<string[]> {
  const texts: string[] = [];
  await page.goto(`/admin/documentos/pendientes?titular=${owner}`);
  await expect(page.getByRole('heading', { level: 1, name: 'Documentos pendientes' })).toBeVisible();
  for (let guard = 0; guard < 100; guard += 1) {
    await expect(settled(page)).toBeVisible();
    const rows = results(page).getByRole('listitem').filter({ hasText: fullName });
    for (let i = 0; i < (await rows.count()); i += 1) texts.push(await rows.nth(i).innerText());
    const next = page.getByRole('button', { name: 'Siguiente' });
    if (!(await next.isVisible()) || (await next.isDisabled())) return texts;
    const before = await results(page).getByRole('listitem').first().innerText();
    await next.click();
    await expect(results(page).getByRole('listitem').first()).not.toHaveText(before);
  }
  throw new Error('the inbox has more pages than any E2E school can hold');
}

/** Clicks the link of the row of `fullName` that shows `label`, wherever it is. */
async function openRow(page: Page, owner: 'DEPORTISTA' | 'TUTOR', fullName: string, label: string): Promise<void> {
  const clicked = await walkInbox(page, owner, async (rows) => {
    const row = rows.filter({ hasText: fullName }).filter({ hasText: label });
    if ((await row.count()) === 0) return undefined;
    await row.first().getByRole('link').click();
    return true;
  });
  expect(clicked, `a row of ${fullName} with ${label}`).toHaveLength(1);
}

/** Read-only: the current document of one owner and type. */
function currentDocument(column: 'deportista_id' | 'tutor_id', owner: string, tipo: string): string {
  const out = dbQuery(`COPY (SELECT id FROM gestion_patin.documento WHERE ${column} = :'owner' AND tipo = :'tipo' AND vigente) TO STDOUT;\n`, { owner, tipo });
  const lines = out.split('\n').filter((l) => l !== '');
  expect(lines, `exactly one current ${tipo}`).toHaveLength(1);
  return lines[0]!;
}

async function familyPost(url: string, tipo: string | null): Promise<APIResponse> {
  return familyContext.request.post(url, {
    multipart: { ...(tipo ? { tipo } : {}), archivo: { name: 'documento.pdf', mimeType: 'application/pdf', buffer: syntheticPdf(Buffer.from(randomUUID())) } },
    headers: { ...(await csrfHeaders(familyContext.request)), 'Idempotency-Key': randomUUID() },
  });
}

async function review(page: Page, label: string, action: 'Aprobar' | 'Observar'): Promise<void> {
  await ficha(page, label).getByRole('button', { name: `${action} documento: ${label}`, exact: true }).click();
  const dialog = page.getByRole('dialog');
  if (action === 'Observar') await dialog.getByLabel('Motivo de la observación').fill(`Revisar ${suffix}`);
  await dialog.getByRole('button', { name: action === 'Aprobar' ? 'Confirmar aprobación' : 'Confirmar observación' }).click();
  await expect(ficha(page, label)).toContainText(action === 'Aprobar' ? 'Aprobado' : 'Observado');
}

test.beforeAll(async ({ browser }) => {
  if (OBJECTS_DIR === '') throw new Error('E2E_STORAGE_OBJECTS_DIR is not set: run .github/scripts/frontend-e2e.sh.');
  if (!hasDisposableDb()) throw new Error('E2E_DB_CONTAINER is not set: the documents are read through the disposable database.');
  browserRef = browser;
  adminContext = await browser.newContext({ baseURL: 'http://127.0.0.1:5173', viewport: { width: 1100, height: 900 } });
  admin = await adminContext.newPage();
  admin.on('console', (message) => adminConsole.push(message.text()));
  admin.on('pageerror', (error) => adminConsole.push(`pageerror: ${error.message}`));
  await loginAdminUi(admin);
  const seed = new AdminSeed(adminContext.request);
  const anonymous = await browser.newContext({ baseURL: 'http://127.0.0.1:5173' });
  family = await seed.createFamilyWithUser(anonymous.request, FAMILY_NAME, {
    nombre: 'Marta', apellido: 'Prueba', email: `pendientes.${suffix}@example.test`, password: `Fam-${randomBytes(8).toString('hex')}`,
  });
  await anonymous.close();
  athleteId = (await seed.createAthlete({ nombre: ATHLETE, apellido: 'Sintetico' })).id;
  await seed.link(family.familyId, [athleteId]);
  tutorId = (await seed.createTutor(family.familyId, { nombre: TUTOR, apellido: 'Sintetico', parentesco: 'Madre' })).id;

  familyContext = await browserRef.newContext({ baseURL: 'http://127.0.0.1:5173' });
  const familyPage = await familyContext.newPage();
  await familyPage.goto('/login');
  await familyPage.getByLabel(/^Correo electrónico/).fill(family.email);
  await familyPage.getByLabel(/^Contraseña/).fill(family.password);
  await familyPage.getByRole('button', { name: 'Ingresar' }).click();
  await expect(familyPage).toHaveURL(/\/familia$/);
  for (const [url, tipo] of [
    [`/api/familia/deportistas/${athleteId}/documentos`, 'DNI_DEPORTISTA_FRENTE'],
    [`/api/familia/deportistas/${athleteId}/documentos`, 'DNI_DEPORTISTA_DORSO'],
    [`/api/familia/tutores/${tutorId}/documentos`, 'DNI_TUTOR_FRENTE'],
  ] as const) expect((await familyPost(url, tipo)).status(), `upload ${tipo}`).toBe(201);
});

test.afterAll(async () => {
  await familyContext?.close();
  await adminContext?.close();
});

test('the inbox lists the new documents of athletes and tutors, links to their fichas and drops what is reviewed', async () => {
  test.setTimeout(240_000);
  const athleteRows = await rowTexts(admin, 'DEPORTISTA', ATHLETE_FULL);
  expect(athleteRows).toHaveLength(2);
  expect(athleteRows[0]).toContain(FRONT); // oldest first: the front was uploaded before the back
  expect(athleteRows[1]).toContain(BACK);
  for (const text of athleteRows) {
    expect(text).toContain('Deportista');
    expect(text).not.toContain(REPLACEMENT);
  }
  expect(await rowTexts(admin, 'TUTOR', ATHLETE_FULL)).toHaveLength(0); // the filter really filters

  const tutorRows = await rowTexts(admin, 'TUTOR', TUTOR_FULL);
  expect(tutorRows).toHaveLength(1);
  expect(tutorRows[0]).toContain('Tutor');
  expect(tutorRows[0]).toContain(TUTOR_FRONT);
  expect(await rowTexts(admin, 'DEPORTISTA', TUTOR_FULL)).toHaveLength(0);

  // The link goes to the tutor's ficha, where the review happens; once approved, the document leaves the inbox.
  await openRow(admin, 'TUTOR', TUTOR_FULL, TUTOR_FRONT);
  await expect(admin).toHaveURL(new RegExp(`/admin/familias/${family.familyId}/tutores/${tutorId}$`));
  await expect(admin.getByRole('heading', { level: 1 })).toContainText(TUTOR);
  await review(admin, TUTOR_FRONT, 'Aprobar');
  expect(await rowTexts(admin, 'TUTOR', TUTOR_FULL)).toHaveLength(0);

  // The athlete's front goes the same way through its own ficha, and only that one leaves.
  await openRow(admin, 'DEPORTISTA', ATHLETE_FULL, FRONT);
  await expect(admin).toHaveURL(new RegExp(`/admin/deportistas/${athleteId}$`));
  await review(admin, FRONT, 'Aprobar');
  const left = await rowTexts(admin, 'DEPORTISTA', ATHLETE_FULL);
  expect(left).toHaveLength(1);
  expect(left[0]).toContain(BACK);
});

test('an observed document leaves the inbox and its replacement comes back marked as a replacement', async () => {
  test.setTimeout(240_000);
  await admin.goto(`/admin/deportistas/${athleteId}`);
  await expect(admin.getByRole('heading', { level: 1 })).toContainText(ATHLETE);
  await review(admin, BACK, 'Observar');
  expect(await rowTexts(admin, 'DEPORTISTA', ATHLETE_FULL)).toHaveLength(0);

  const observed = currentDocument('deportista_id', athleteId, 'DNI_DEPORTISTA_DORSO');
  expect((await familyPost(`/api/familia/deportistas/${athleteId}/documentos/${observed}/reemplazo`, null)).status()).toBe(201);

  const rows = await rowTexts(admin, 'DEPORTISTA', ATHLETE_FULL);
  expect(rows).toHaveLength(1);
  expect(rows[0]).toContain(BACK);
  expect(rows[0]).toContain(REPLACEMENT);
  // The replaced (historical) version is not listed: the only current one is the successor.
  expect(currentDocument('deportista_id', athleteId, 'DNI_DEPORTISTA_DORSO')).not.toBe(observed);
});

test('the route is ADMIN-only, takes no scope, validates the filter and exposes no file data', async () => {
  // Its own pending document: the tutor's back, which no other test of this file uploads, so this test needs nothing from the others.
  expect((await familyPost(`/api/familia/tutores/${tutorId}/documentos`, 'DNI_TUTOR_DORSO')).status(), 'upload DNI_TUTOR_DORSO').toBe(201);
  const csrf = await csrfHeaders(adminContext.request);
  const ok = await adminContext.request.get('/api/admin/documentos/pendientes?size=100&escuelaId=' + randomUUID(), { headers: csrf });
  expect(ok.status()).toBe(200);
  expect(ok.headers()['cache-control']).toContain('no-store');
  const body = (await ok.json()) as { contenido: Record<string, unknown>[]; totalElementos: number };
  expect(body.totalElementos).toBeGreaterThanOrEqual(1);
  for (const item of body.contenido) {
    expect(Object.keys(item).sort()).toEqual(['creadoEn', 'familiaId', 'id', 'reemplazo', 'tipo', 'titularId', 'titularNombre', 'titularTipo']);
  }
  expect(JSON.stringify(body)).not.toMatch(/storage|sha|bucket|nombre_original|mime|archivo|version/i);
  const created = body.contenido.map((item) => item.creadoEn as string);
  expect([...created].sort()).toEqual(created); // oldest first
  expect((await adminContext.request.get('/api/admin/documentos/pendientes?titular=OTRO', { headers: csrf })).status()).toBe(400);

  expect((await familyContext.request.get('/api/admin/documentos/pendientes')).status()).toBe(403);
  const anonymous = await browserRef.newContext({ baseURL: 'http://127.0.0.1:5173' });
  expect((await anonymous.request.get('/api/admin/documentos/pendientes')).status()).toBe(401);
  await anonymous.close();
  expect(adminConsole.filter((line) => line.startsWith('pageerror:'))).toEqual([]);
});
