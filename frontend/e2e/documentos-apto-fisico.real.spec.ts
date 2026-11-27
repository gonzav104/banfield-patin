import { randomBytes } from 'node:crypto';
import { expect, test, type Browser, type BrowserContext, type Page } from '@playwright/test';
import { dbQuery, hasDisposableDb } from './support/db';
import { browserJpeg } from './support/documents';
import { addPrivacyHazards, inspectJpeg } from './support/jpeg';
import { AdminSeed, loginAdminUi, schoolToday, type SeededFamily } from './support/seed';

/**
 * REAL apto físico (FE-14/U21): browser -> Vite proxy -> Spring backend with the upload ENABLED -> PostgreSQL, against the two TEST DOUBLES
 * of .github/scripts/frontend-e2e.sh (Storage and clamd). Two families linked to the SAME athlete and an administrator:
 * the first family uploads the apto with its three data; the second sees the state but never the data or the reason; ADMIN reads the
 * data, opens the file through the audited route, observes with a reason, the uploading family reads it and replaces the apto with its own
 * data, ADMIN approves and then corrects a datum (which withdraws the approval); finally the second family's link is revoked.
 *
 * Nothing medical may appear in the address, the browser storages or the console of a family page. The values are synthetic.
 */
test.describe.configure({ mode: 'serial' });

const OBJECTS_DIR = process.env.E2E_STORAGE_OBJECTS_DIR ?? '';
test.skip(OBJECTS_DIR === '' && !process.env.CI, 'Needs .github/scripts/frontend-e2e.sh: the upload enabled against the doubles.');

const suffix = randomBytes(3).toString('hex');
const YEAR = Number(schoolToday().slice(0, 4));
const ATHLETE = `E2E-Apto-${suffix}`;
const DOCTOR = `Dra E2E ${suffix}`;
const LICENSE = `MP-${suffix}-1`;
const ISSUED = `${YEAR}-03-01`;
const REASON = `Motivo E2E ${suffix}`;
const NEW_DOCTOR = `Dr Reemplazo ${suffix}`;
const NEW_LICENSE = `MP-${suffix}-2`;
const FIXED_LICENSE = `MP-${suffix}-3`;
const SENSITIVE = [DOCTOR, LICENSE, REASON, NEW_DOCTOR, NEW_LICENSE, FIXED_LICENSE];

let browserRef: Browser;
let adminContext: BrowserContext;
let adminPage: Page;
let familyA: SeededFamily;
let familyB: SeededFamily;
let athleteId = '';
let seasonId = '';
let photo: Buffer;
let aptoId = '';

const sensitivePattern = () => new RegExp(SENSITIVE.map((v) => v.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')).join('|'));
const noHorizontalOverflow = (page: Page) => page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth);

/** Read-only: every version of the apto of the athlete, oldest first. */
function aptoRows(): { id: string; estado: string; vigente: string; doctor: string; license: string; issued: string; byFamily: string; reviewedBy: string }[] {
  const out = dbQuery(
    `COPY (SELECT d.id, d.estado, d.vigente::text, d.medico_nombre, d.medico_matricula, d.fecha_emision::text, coalesce(u.familia_id::text, 'sin-familia'), coalesce(d.revisado_por::text, 'sin-revisor')
           FROM gestion_patin.documento d JOIN gestion_patin.usuario u ON u.id = d.cargado_por
           WHERE d.deportista_id = :'athlete' AND d.tipo = 'APTO_FISICO' ORDER BY d.creado_en, d.id) TO STDOUT;\n`,
    { athlete: athleteId },
  );
  return out
    .split('\n')
    .filter((line) => line !== '')
    .map((line) => {
      const [id = '', estado = '', vigente = '', doctor = '', license = '', issued = '', byFamily = '', reviewedBy = ''] = line.split('\t');
      return { id, estado, vigente, doctor, license, issued, byFamily, reviewedBy };
    });
}

function auditCount(action: string, resourceId: string): number {
  return Number(dbQuery("COPY (SELECT count(*) FROM gestion_patin.auditoria WHERE accion = :'action' AND recurso_id = :'id') TO STDOUT;\n", { action, id: resourceId }).trim());
}

function auditDetail(action: string, resourceId: string): string {
  return dbQuery("COPY (SELECT detalle::text FROM gestion_patin.auditoria WHERE accion = :'action' AND recurso_id = :'id' ORDER BY id DESC LIMIT 1) TO STDOUT;\n", { action, id: resourceId });
}

async function familyPage(family: SeededFamily, viewport?: { width: number; height: number }): Promise<{ context: BrowserContext; page: Page; consoleLines: string[] }> {
  const context = await browserRef.newContext({ baseURL: 'http://127.0.0.1:5173', ...(viewport ? { viewport } : {}) });
  const page = await context.newPage();
  const consoleLines: string[] = [];
  page.on('console', (message) => consoleLines.push(message.text()));
  page.on('pageerror', (error) => consoleLines.push(`pageerror: ${error.message}`));
  await page.goto('/login');
  await page.getByLabel(/^Correo electrónico/).fill(family.email);
  await page.getByLabel(/^Contraseña/).fill(family.password);
  await page.getByRole('button', { name: 'Ingresar' }).click();
  await expect(page).toHaveURL(/\/familia$/);
  return { context, page, consoleLines };
}

const aptoRegion = (page: Page) => page.getByRole('region', { name: 'Apto físico' });

async function openFamilySheet(page: Page): Promise<void> {
  await page.goto(`/familia/deportistas/${athleteId}`);
  await expect(page.getByRole('heading', { level: 1 })).toContainText(ATHLETE);
  await expect(aptoRegion(page)).toBeVisible();
}

async function openAdminDetail(): Promise<void> {
  await adminPage.goto(`/admin/deportistas/${athleteId}`);
  await expect(adminPage.getByRole('heading', { level: 1 })).toContainText(ATHLETE);
  await expect(aptoRegion(adminPage)).toBeVisible();
}

async function fillData(region: ReturnType<typeof aptoRegion>, data: { doctor: string; license: string; issued: string }): Promise<void> {
  await region.getByLabel('Fecha de emisión').fill(data.issued);
  await region.getByLabel('Nombre del médico').fill(data.doctor);
  await region.getByLabel('Matrícula').fill(data.license);
}

async function expectNoLeaks(page: Page, consoleLines: string[]): Promise<void> {
  expect(page.url()).not.toMatch(sensitivePattern());
  const stored = await page.evaluate(async () => ({
    local: JSON.stringify({ ...localStorage }),
    session: JSON.stringify({ ...sessionStorage }),
    caches: JSON.stringify(await caches.keys()),
    databases: JSON.stringify(((await indexedDB.databases?.()) ?? []).map((d) => d.name)),
  }));
  expect(JSON.stringify(stored)).not.toMatch(sensitivePattern());
  expect(consoleLines.join('\n')).not.toMatch(sensitivePattern());
}

test.beforeAll(async ({ browser }) => {
  if (OBJECTS_DIR === '') throw new Error('E2E_STORAGE_OBJECTS_DIR is not set: run .github/scripts/frontend-e2e.sh (upload enabled against the doubles).');
  if (!hasDisposableDb()) throw new Error('E2E_DB_CONTAINER is not set: the stored apto is read through the disposable database.');
  browserRef = browser;
  adminContext = await browser.newContext({ baseURL: 'http://127.0.0.1:5173' });
  adminPage = await adminContext.newPage();
  await loginAdminUi(adminPage);
  const seed = new AdminSeed(adminContext.request);
  const anonymous = await browser.newContext({ baseURL: 'http://127.0.0.1:5173' });
  familyA = await seed.createFamilyWithUser(anonymous.request, `E2E Apto A ${suffix}`, { nombre: 'Marta', apellido: 'Prueba', email: `apto.a.${suffix}@example.test`, password: `Fam-${randomBytes(8).toString('hex')}` });
  familyB = await seed.createFamilyWithUser(anonymous.request, `E2E Apto B ${suffix}`, { nombre: 'Pablo', apellido: 'Prueba', email: `apto.b.${suffix}@example.test`, password: `Fam-${randomBytes(8).toString('hex')}` });
  await anonymous.close();
  athleteId = (await seed.createAthlete({ nombre: ATHLETE, apellido: 'Sintetico' })).id;
  await seed.link(familyA.familyId, [athleteId]);
  await seed.link(familyB.familyId, [athleteId]); // the SAME athlete, a second independent family
  const season = await seed.season(YEAR);
  seasonId = season.id;
  // The portal lists an athlete's seasons only up to the ACTIVE one (a family never sees a season after it): with no active season the list is empty.
  if (!season.activa) await seed.activateSeason(season.id);
  await seed.addSportsRow(athleteId, seasonId, { categoria: 'Sub-11-Apto', rama: 'Femenina', divisional: 'Apto A', numeroCorredor: '0916' });
  photo = Buffer.from(addPrivacyHazards(new Uint8Array(await browserJpeg(adminPage, { width: 1600, height: 1000 }))));
  expect(inspectJpeg(photo)).toMatchObject({ isJpeg: true, hasExif: true, hasGps: true });
});

test.afterAll(async () => {
  await adminContext?.close();
});

test('mobile 360 px: the family starts at «Falta», uploads the apto with its three data and sees it in review', async () => {
  const { context, page, consoleLines } = await familyPage(familyA, { width: 360, height: 800 });
  const availability = await page.request.get('/api/familia/documentos/disponibilidad');
  expect(await availability.json()).toMatchObject({ habilitada: true });
  await openFamilySheet(page);
  const region = aptoRegion(page);
  await expect(region.getByText('Falta')).toBeVisible();
  await expect(region.getByText(`Vigente hasta el 31 de diciembre de ${YEAR}.`)).toBeVisible();
  // Only 2026 has a confirmed rule (ReglaVigenciaApto): any other year must show the provisional notice. When a season is confirmed, update both.
  await expect(region.getByText(/todavía no están confirmados/)).toHaveCount(YEAR === 2026 ? 0 : 1);
  expect(await noHorizontalOverflow(page)).toBe(true);

  await region.getByLabel('Sacar foto del apto físico', { exact: true }).setInputFiles({ name: `IMG_apto_${ATHLETE}.jpg`, mimeType: 'image/jpeg', buffer: photo });
  await expect(region.getByRole('img', { name: 'Vista previa del apto físico' })).toBeVisible();
  // client-side validation: nothing is sent while the data are invalid
  await region.getByRole('button', { name: 'Enviar' }).click();
  await expect(region.getByText('Ingresá el nombre del médico.')).toBeVisible();
  await fillData(region, { doctor: DOCTOR, license: LICENSE, issued: ISSUED });
  expect(await noHorizontalOverflow(page)).toBe(true);
  const upload = page.waitForResponse((r) => r.request().method() === 'POST' && r.url().endsWith(`/api/familia/deportistas/${athleteId}/documentos`));
  await region.getByRole('button', { name: 'Enviar' }).click();
  expect((await upload).status()).toBe(201);
  await expect(region.getByText('En revisión')).toBeVisible();
  await expect(region.getByText(LICENSE)).toBeVisible();

  const [row, ...extra] = aptoRows();
  expect(extra).toEqual([]);
  aptoId = row!.id;
  expect(row).toMatchObject({ estado: 'CARGADO', vigente: 'true', doctor: DOCTOR, license: LICENSE, issued: ISSUED, byFamily: familyA.familyId });
  await expectNoLeaks(page, consoleLines);
  await context.close();
});

test('the second family of the same athlete sees the state, never the data, and has no way to the file', async () => {
  const { context, page, consoleLines } = await familyPage(familyB);
  await openFamilySheet(page);
  const region = aptoRegion(page);
  await expect(region.getByText('En revisión')).toBeVisible();
  await expect(region.getByText(/Este apto lo cargó otra familia vinculada/)).toBeVisible();
  await expect(region).not.toContainText(DOCTOR);
  await expect(region).not.toContainText(LICENSE);
  const api = await page.request.get(`/api/familia/deportistas/${athleteId}/temporadas/${seasonId}/apto-fisico`);
  expect(api.status()).toBe(200);
  const body = await api.text();
  expect(body).not.toMatch(sensitivePattern());
  expect(JSON.parse(body)).toMatchObject({ estado: 'EN_REVISION', documento: { id: aptoId, datosVisibles: false } });
  const list = await page.request.get(`/api/familia/deportistas/${athleteId}/documentos?page=0&size=100`);
  expect(await list.text()).not.toMatch(sensitivePattern());
  expect((await page.request.get(`/api/familia/deportistas/${athleteId}/documentos/${aptoId}/archivo`)).status()).not.toBe(200);
  await expectNoLeaks(page, consoleLines);
  await context.close();
});

test('ADMIN reads the data the family declared, opens the file through the audited route and observes with a reason', async () => {
  await openAdminDetail();
  const region = aptoRegion(adminPage);
  await expect(region.getByText('En revisión')).toBeVisible();
  await expect(region.getByText(LICENSE)).toBeVisible();
  await expect(region.getByText(DOCTOR)).toBeVisible();
  // the apto is not repeated in the generic list of documents
  await expect(adminPage.getByRole('region', { name: 'Documentos' }).getByText('Apto físico')).toHaveCount(0);

  expect(auditCount('DOCUMENTO_DESCARGADO', aptoId)).toBe(0);
  await region.getByRole('button', { name: 'Abrir documento: Apto físico' }).click();
  await expect(region.getByRole('img', { name: 'Documento: Apto físico' })).toBeVisible();
  expect(auditCount('DOCUMENTO_DESCARGADO', aptoId)).toBe(1);
  await region.getByRole('button', { name: 'Cerrar' }).click();

  await region.getByRole('button', { name: 'Observar documento: Apto físico' }).click();
  await adminPage.getByLabel('Motivo de la observación').fill(REASON);
  await adminPage.getByRole('button', { name: 'Confirmar observación' }).click();
  await expect(region.getByText('Documento observado.')).toBeVisible();
  await expect(region.getByText('Observado').first()).toBeVisible();
  expect(aptoRows()[0]).toMatchObject({ estado: 'OBSERVADO' });
  // the correction is not offered for an observed apto: the family replaces it
  await expect(region.getByRole('button', { name: 'Corregir los datos del apto físico' })).toHaveCount(0);
});

test('the family that uploaded reads the reason; the other family only learns it must be replaced; each replaces with its own data', async () => {
  const a = await familyPage(familyA);
  await openFamilySheet(a.page);
  await expect(aptoRegion(a.page)).toContainText(REASON);
  const b = await familyPage(familyB);
  await openFamilySheet(b.page);
  const regionB = aptoRegion(b.page);
  await expect(regionB.getByText('Estado del apto: Observado')).toBeVisible();
  await expect(regionB.getByText(/El club observó este apto: hay que reemplazarlo/)).toBeVisible();
  await expect(regionB).not.toContainText(REASON);
  for (const page of [a.page, b.page]) {
    const list = await page.request.get(`/api/familia/deportistas/${athleteId}/documentos?page=0&size=100`);
    const text = await list.text();
    if (page === b.page) expect(text).not.toContain(REASON);
    else expect(text).toContain(REASON);
  }
  await expect(regionB.getByLabel('Sacar foto del apto físico (reemplazo)', { exact: true })).toBeVisible(); // it can be replaced by either family
  await b.context.close();

  // A replaces with its own, new data: the old version stays as history
  const regionA = aptoRegion(a.page);
  await regionA.getByLabel('Sacar foto del apto físico (reemplazo)', { exact: true }).setInputFiles({ name: 'nueva.jpg', mimeType: 'image/jpeg', buffer: photo });
  await fillData(regionA, { doctor: NEW_DOCTOR, license: NEW_LICENSE, issued: ISSUED });
  const replace = a.page.waitForResponse((r) => r.request().method() === 'POST' && r.url().endsWith(`/documentos/${aptoId}/reemplazo`));
  await regionA.getByRole('button', { name: 'Enviar' }).click();
  expect((await replace).status()).toBe(201);
  await expect(regionA.getByText('En revisión')).toBeVisible();
  const rows = aptoRows();
  expect(rows).toHaveLength(2);
  expect(rows[0]).toMatchObject({ id: aptoId, estado: 'OBSERVADO', vigente: 'false', doctor: DOCTOR });
  expect(rows[1]).toMatchObject({ estado: 'CARGADO', vigente: 'true', doctor: NEW_DOCTOR, license: NEW_LICENSE, byFamily: familyA.familyId });
  aptoId = rows[1]!.id;
  await expectNoLeaks(a.page, a.consoleLines);
  await a.context.close();
});

test('ADMIN approves and then corrects a datum: the screen warns that the approval is withdrawn, and the server does it with an audit of field names only', async () => {
  await openAdminDetail();
  const region = aptoRegion(adminPage);
  await region.getByRole('button', { name: 'Aprobar documento: Apto físico' }).click();
  await adminPage.getByRole('button', { name: 'Confirmar aprobación' }).click();
  await expect(region.getByText('Documento aprobado.')).toBeVisible();
  await expect(region.getByText('Cumplido')).toBeVisible();
  expect(aptoRows()[1]).toMatchObject({ estado: 'APROBADO' });
  expect(aptoRows()[1]!.reviewedBy).not.toBe('sin-revisor');

  await region.getByRole('button', { name: 'Corregir los datos del apto físico' }).click();
  const dialog = adminPage.getByRole('dialog', { name: 'Corregir los datos del apto' });
  await expect(dialog.getByText(/retira su aprobación/)).toBeVisible();
  await expect(dialog.getByRole('button', { name: 'Corregir y retirar la aprobación' })).toBeDisabled(); // nothing changed yet
  await dialog.getByLabel('Matrícula').fill(FIXED_LICENSE);
  await dialog.getByRole('button', { name: 'Corregir y retirar la aprobación' }).click();
  await expect(region.getByText('Datos corregidos. El apto vuelve a revisión.')).toBeVisible();
  await expect(region.getByText('En revisión')).toBeVisible();
  await expect(region.getByText(FIXED_LICENSE)).toBeVisible();

  expect(aptoRows()[1]).toMatchObject({ estado: 'CARGADO', license: FIXED_LICENSE, reviewedBy: 'sin-revisor' });
  expect(auditCount('DOCUMENTO_APTO_CORREGIDO', aptoId)).toBe(1);
  const detail = auditDetail('DOCUMENTO_APTO_CORREGIDO', aptoId);
  expect(detail).toContain('medicoMatricula');
  expect(detail).not.toMatch(sensitivePattern());
  // the approval can be given again on the new revision
  await expect(region.getByRole('button', { name: 'Aprobar documento: Apto físico' })).toBeEnabled();
});

test('the pending inbox lists the apto and leads to the ficha where it is reviewed', async () => {
  await adminPage.goto('/admin/documentos/pendientes');
  const link = adminPage.getByRole('link', { name: new RegExp(`^Revisar Apto físico de .*${ATHLETE}`) });
  await expect(link).toBeVisible();
  await link.click();
  await expect(adminPage).toHaveURL(new RegExp(`/admin/deportistas/${athleteId}$`));
  await expect(aptoRegion(adminPage).getByRole('button', { name: 'Aprobar documento: Apto físico' })).toBeVisible();
});

test('revoking the link of the second family ends its access to the athlete and to the apto', async () => {
  const seed = new AdminSeed(adminContext.request);
  await seed.revoke(familyB.familyId, athleteId);
  const { context, page } = await familyPage(familyB);
  const api = await page.request.get(`/api/familia/deportistas/${athleteId}/temporadas/${seasonId}/apto-fisico`);
  expect(api.status()).toBe(404);
  await page.goto(`/familia/deportistas/${athleteId}`);
  await expect(page.getByText(/No encontramos este deportista/)).toBeVisible();
  await expect(aptoRegion(page)).toHaveCount(0);
  await context.close();
});
