import { randomBytes, randomUUID } from 'node:crypto';
import { expect, test, type APIResponse, type Browser, type BrowserContext, type Page } from '@playwright/test';
import { dbQuery, hasDisposableDb } from './support/db';
import { syntheticPdf } from './support/documents';
import { AdminSeed, csrfHeaders, loginAdminUi, type SeededFamily } from './support/seed';

/**
 * REAL partial document checklist of the athlete's DNI (U20): browser -> Vite proxy -> Spring backend -> PostgreSQL, with the
 * document pipeline ENABLED against the Storage and clamd test doubles started by frontend-e2e.sh. The family uploads through the
 * real API (as in the U16 and pending-documents specs); the administrator reviews through the real ficha; the checklist is read
 * from the family sheet, the family dashboard and the administrative ficha, and it must follow every real state change:
 * missing -> in review -> approved / observed (with its reason) -> replaced -> approved. It is PARTIAL by contract and never
 * shows a percentage or a registration state.
 */
test.describe.configure({ mode: 'serial' });

const OBJECTS_DIR = process.env.E2E_STORAGE_OBJECTS_DIR ?? '';
test.skip(OBJECTS_DIR === '' && !process.env.CI, 'Needs .github/scripts/frontend-e2e.sh: the document pipeline enabled against the Storage and clamd doubles.');

const suffix = randomBytes(3).toString('hex');
const FAMILY_NAME = `E2E Checklist Familia ${suffix}`;
const OTHER_FAMILY_NAME = `E2E Checklist Ajena ${suffix}`;
const ATHLETE = `E2E-Check-Dep-${suffix}`;
const OTHER_ATHLETE = `E2E-Check-Ajeno-${suffix}`;
const FRONT = 'DNI – frente';
const BACK = 'DNI – dorso';
const REASON = `El dorso se ve cortado ${suffix}`;
const SUMMARY = 'DNI del deportista (frente y dorso): ';

let browserRef: Browser;
let adminContext: BrowserContext;
let admin: Page;
let familyContext: BrowserContext;
let family: SeededFamily;
let otherFamily: SeededFamily;
let athleteId = '';
let otherAthleteId = '';
const consoleLines: string[] = [];

const checklist = (page: Page) => page.getByRole('region', { name: 'Checklist de documentación' });
const checklistItem = (page: Page, label: string) => checklist(page).getByRole('listitem').filter({ hasText: label });
const documentsSection = (page: Page) => page.getByRole('region', { name: 'Documentos', exact: true });
const adminDocument = (page: Page, label: string) => documentsSection(page).getByRole('listitem').filter({ has: page.getByRole('heading', { name: label, exact: true }) });

/** Read-only: the current document of the athlete for one DNI side. */
function currentDocument(tipo: string): string {
  const out = dbQuery("COPY (SELECT id FROM gestion_patin.documento WHERE deportista_id = :'owner' AND tipo = :'tipo' AND vigente) TO STDOUT;\n", { owner: athleteId, tipo });
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
  await adminDocument(page, label).getByRole('button', { name: `${action} documento: ${label}`, exact: true }).click();
  const dialog = page.getByRole('dialog');
  if (action === 'Observar') await dialog.getByLabel('Motivo de la observación').fill(REASON);
  await dialog.getByRole('button', { name: action === 'Aprobar' ? 'Confirmar aprobación' : 'Confirmar observación' }).click();
  await expect(adminDocument(page, label)).toContainText(action === 'Aprobar' ? 'Aprobado' : 'Observado');
}

async function familySheet(page: Page): Promise<void> {
  await page.goto(`/familia/deportistas/${athleteId}`);
  await expect(page.getByRole('heading', { level: 1 })).toContainText(ATHLETE);
  await expect(checklist(page)).toBeVisible();
}

async function adminFicha(page: Page): Promise<void> {
  await page.goto(`/admin/deportistas/${athleteId}`);
  await expect(page.getByRole('heading', { level: 1 })).toContainText(ATHLETE);
  await expect(checklist(page)).toBeVisible();
}

async function login(page: Page, who: SeededFamily): Promise<void> {
  await page.goto('/login');
  await page.getByLabel(/^Correo electrónico/).fill(who.email);
  await page.getByLabel(/^Contraseña/).fill(who.password);
  await page.getByRole('button', { name: 'Ingresar' }).click();
  await expect(page).toHaveURL(/\/familia$/);
}

test.beforeAll(async ({ browser }) => {
  if (OBJECTS_DIR === '') throw new Error('E2E_STORAGE_OBJECTS_DIR is not set: run .github/scripts/frontend-e2e.sh.');
  if (!hasDisposableDb()) throw new Error('E2E_DB_CONTAINER is not set: the documents are read through the disposable database.');
  browserRef = browser;
  adminContext = await browser.newContext({ baseURL: 'http://127.0.0.1:5173', viewport: { width: 1100, height: 900 } });
  admin = await adminContext.newPage();
  admin.on('console', (message) => consoleLines.push(message.text()));
  admin.on('pageerror', (error) => consoleLines.push(`pageerror: ${error.message}`));
  await loginAdminUi(admin);
  const seed = new AdminSeed(adminContext.request);
  const anonymous = await browser.newContext({ baseURL: 'http://127.0.0.1:5173' });
  const mk = (name: string, tag: string) =>
    seed.createFamilyWithUser(anonymous.request, name, { nombre: 'Marta', apellido: 'Prueba', email: `checklist.${tag}.${suffix}@example.test`, password: `Fam-${randomBytes(8).toString('hex')}` });
  family = await mk(FAMILY_NAME, 'a');
  otherFamily = await mk(OTHER_FAMILY_NAME, 'b');
  await anonymous.close();
  athleteId = (await seed.createAthlete({ nombre: ATHLETE, apellido: 'Sintetico' })).id;
  otherAthleteId = (await seed.createAthlete({ nombre: OTHER_ATHLETE, apellido: 'Sintetico' })).id;
  await seed.link(family.familyId, [athleteId]);
  await seed.link(otherFamily.familyId, [otherAthleteId]);

  familyContext = await browser.newContext({ baseURL: 'http://127.0.0.1:5173', viewport: { width: 390, height: 844 } });
  await login(await familyContext.newPage(), family);
});

test.afterAll(async () => {
  await familyContext?.close();
  await adminContext?.close();
});

test('a new athlete shows both DNI sides missing, and the checklist says it is partial and never claims eligibility', async () => {
  const fam = await familyContext.newPage();
  fam.on('console', (message) => consoleLines.push(message.text()));
  fam.on('pageerror', (error) => consoleLines.push(`pageerror: ${error.message}`));
  await familySheet(fam);
  await expect(checklist(fam)).toContainText('Este checklist es parcial: solo revisa el DNI del deportista (frente y dorso). No indica si el deportista está listo para empadronarse.');
  await expect(checklistItem(fam, FRONT)).toContainText('Falta');
  await expect(checklistItem(fam, BACK)).toContainText('Falta');
  expect(await checklist(fam).innerText()).not.toMatch(/%|LISTO_PARA_PRESENTAR|EMPADRONADO|completo/i);
  // 390 px: the checklist fits without horizontal scrolling.
  expect(await fam.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);

  await fam.goto('/familia');
  const card = fam.getByRole('link', { name: new RegExp(`^${ATHLETE}`) });
  await expect(card).toContainText(`${SUMMARY}falta`);
  await fam.close();

  await adminFicha(admin);
  await expect(checklistItem(admin, FRONT)).toContainText('Falta');
  await expect(checklistItem(admin, BACK)).toContainText('Falta');
});

test('the checklist follows the real states: in review, approved, observed with its reason, replaced and approved', async () => {
  test.setTimeout(240_000);
  expect((await familyPost(`/api/familia/deportistas/${athleteId}/documentos`, 'DNI_DEPORTISTA_FRENTE')).status()).toBe(201);
  expect((await familyPost(`/api/familia/deportistas/${athleteId}/documentos`, 'DNI_DEPORTISTA_DORSO')).status()).toBe(201);

  const fam = await familyContext.newPage();
  await familySheet(fam);
  await expect(checklistItem(fam, FRONT)).toContainText('En revisión');
  await expect(checklistItem(fam, BACK)).toContainText('En revisión');
  await fam.goto('/familia');
  await expect(fam.getByRole('link', { name: new RegExp(`^${ATHLETE}`) })).toContainText(`${SUMMARY}en revisión`);

  // The administrator sees the same states and decides in the ficha: the checklist updates right after each review.
  await adminFicha(admin);
  await expect(checklistItem(admin, FRONT)).toContainText('En revisión');
  await review(admin, FRONT, 'Aprobar');
  await expect(checklistItem(admin, FRONT)).toContainText('Aprobado');
  await expect(checklistItem(admin, BACK)).toContainText('En revisión');
  await review(admin, BACK, 'Observar');
  await expect(checklistItem(admin, BACK)).toContainText('Observado');
  await expect(checklistItem(admin, BACK)).toContainText(`Motivo de la observación: ${REASON}`);

  // The family sees the approved side, the observed one with the reason, and the dashboard line follows the most urgent state.
  await familySheet(fam);
  await expect(checklistItem(fam, FRONT)).toContainText('Aprobado');
  await expect(checklistItem(fam, BACK)).toContainText('Observado');
  await expect(checklistItem(fam, BACK)).toContainText(`Motivo de la observación: ${REASON}`);
  await fam.goto('/familia');
  await expect(fam.getByRole('link', { name: new RegExp(`^${ATHLETE}`) })).toContainText(`${SUMMARY}observado`);
  await expect(fam.getByRole('main')).not.toContainText(REASON); // the reason is for the sheet, not the dashboard

  // The replacement of the observed side: only the successor counts (the observed predecessor is history).
  const observed = currentDocument('DNI_DEPORTISTA_DORSO');
  expect((await familyPost(`/api/familia/deportistas/${athleteId}/documentos/${observed}/reemplazo`, null)).status()).toBe(201);
  expect(currentDocument('DNI_DEPORTISTA_DORSO')).not.toBe(observed);
  await familySheet(fam);
  await expect(checklistItem(fam, BACK)).toContainText('En revisión');
  await expect(checklistItem(fam, BACK)).not.toContainText('Motivo');
  await adminFicha(admin);
  await expect(checklistItem(admin, BACK)).toContainText('En revisión');
  await review(admin, BACK, 'Aprobar');
  await expect(checklistItem(admin, BACK)).toContainText('Aprobado');

  // Both approved: nothing left to do, and the dashboard says exactly what it evaluates.
  await familySheet(fam);
  await expect(checklistItem(fam, FRONT)).toContainText('Aprobado');
  await expect(checklistItem(fam, BACK)).toContainText('Aprobado');
  await expect(checklist(fam).getByRole('button')).toHaveCount(0);
  await fam.goto('/familia');
  await expect(fam.getByRole('link', { name: new RegExp(`^${ATHLETE}`) })).toContainText(`${SUMMARY}aprobado`);
  expect(await fam.getByRole('main').innerText()).not.toMatch(/%|LISTO_PARA_PRESENTAR|EMPADRONADO/i);
  await fam.close();
});

test('the route is scoped: another family and unknown ids are the same 404, roles are not interchangeable, and the answer has no extra fields', async () => {
  const own = await familyContext.request.get(`/api/familia/deportistas/${athleteId}/checklist-documental`);
  expect(own.status()).toBe(200);
  expect(own.headers()['cache-control']).toContain('no-store');
  const body = (await own.json()) as { deportistaId: string; parcial: boolean; alcance: string; requisitos: { tipo: string; estado: string; motivoObservacion: string | null }[] };
  expect(Object.keys(body).sort()).toEqual(['alcance', 'deportistaId', 'parcial', 'requisitos']);
  expect(body).toMatchObject({ deportistaId: athleteId, parcial: true, alcance: 'DNI_DEPORTISTA' });
  expect(body.requisitos.map((r) => `${r.tipo}:${r.estado}`)).toEqual(['DNI_DEPORTISTA_FRENTE:CUMPLIDO', 'DNI_DEPORTISTA_DORSO:CUMPLIDO']);
  expect(JSON.stringify(body)).not.toMatch(/LISTO_PARA_PRESENTAR|EMPADRONADO|porcentaje|storage|sha|bucket/i);

  // Another family's athlete, an unknown id and an administrator's athlete of nobody: the same 404 for a family.
  const foreign = await familyContext.request.get(`/api/familia/deportistas/${otherAthleteId}/checklist-documental`);
  const unknown = await familyContext.request.get(`/api/familia/deportistas/${randomUUID()}/checklist-documental`);
  expect(foreign.status()).toBe(404);
  expect(unknown.status()).toBe(404);
  expect(await foreign.json()).toEqual(await unknown.json());
  expect(((await foreign.json()) as { codigo: string }).codigo).toBe('DEPORTISTA_NO_ENCONTRADO');

  // Roles: a family cannot call the administrative route, an administrator cannot call the family one, and anonymous is 401.
  expect((await familyContext.request.get(`/api/admin/deportistas/${athleteId}/checklist-documental`)).status()).toBe(403);
  expect((await adminContext.request.get(`/api/familia/deportistas/${athleteId}/checklist-documental`)).status()).toBe(403);
  const anonymous = await browserRef.newContext({ baseURL: 'http://127.0.0.1:5173' });
  expect((await anonymous.request.get(`/api/admin/deportistas/${athleteId}/checklist-documental`)).status()).toBe(401);
  expect((await anonymous.request.get(`/api/familia/deportistas/${athleteId}/checklist-documental`)).status()).toBe(401);
  await anonymous.close();

  // The administrator reads the same calculation; an unknown id is a 404 for them too.
  const adminView = await adminContext.request.get(`/api/admin/deportistas/${athleteId}/checklist-documental`);
  expect(adminView.status()).toBe(200);
  expect(await adminView.json()).toEqual(body);
  expect((await adminContext.request.get(`/api/admin/deportistas/${randomUUID()}/checklist-documental`)).status()).toBe(404);

  expect(consoleLines.filter((line) => line.startsWith('pageerror:'))).toEqual([]);
});
