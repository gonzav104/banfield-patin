import { randomBytes } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { expect, test, type Browser, type BrowserContext, type Page } from '@playwright/test';
import { dbQuery, hasDisposableDb } from './support/db';
import { sha256, syntheticPdf } from './support/documents';
import { AdminSeed, csrfHeaders, loginAdminUi, type SeededFamily } from './support/seed';

/**
 * REAL documents of a TUTOR (FE-10/U19): the family uploads the DNI of one of its tutors from the portal, the administrator
 * approves one side and observes the other from the tutor detail, the family replaces only the observed one, the
 * administrator approves it, and the family sees both approved. Browser -> Vite proxy -> Spring backend -> PostgreSQL, with
 * the upload ENABLED against the Storage and clamd test doubles started by frontend-e2e.sh (same setup as documentos-dni).
 * The chain, the stored objects and the audit are read back from the disposable database and the Storage double
 * (read-only). The doubles do NOT replace the opt-in tests against a real Supabase and a real ClamAV.
 */
test.describe.configure({ mode: 'serial' });

const OBJECTS_DIR = process.env.E2E_STORAGE_OBJECTS_DIR ?? '';
test.skip(OBJECTS_DIR === '' && !process.env.CI, 'Needs .github/scripts/frontend-e2e.sh: the upload enabled against the Storage and clamd doubles.');

const suffix = randomBytes(3).toString('hex');
const FAMILY_NAME = `E2E Tutor Familia ${suffix}`;
const OTHER_FAMILY_NAME = `E2E Tutor Ajena ${suffix}`;
const TUTOR_NAME = `Tutor${suffix}`;
const FRONT = 'DNI – frente';
const BACK = 'DNI – dorso';
const ADMIN_FRONT = 'DNI del tutor – frente';
const ADMIN_BACK = 'DNI del tutor – dorso';
const REASON = `El dorso está cortado ${suffix}`;

let browserRef: Browser;
let adminContext: BrowserContext;
let family: SeededFamily;
let otherFamily: SeededFamily;
let tutorId = '';
let tutorDni = '';

const tutorSlot = (page: Page, side: string) => page.getByRole('region', { name: 'Documentos del tutor' }).getByRole('article', { name: side });
const adminRegion = (page: Page) => page.getByRole('region', { name: 'Documentos', exact: true });
const adminRow = (page: Page, label: string) => adminRegion(page).getByRole('listitem').filter({ has: page.getByRole('heading', { name: label, exact: true }) });
const noHorizontalOverflow = (page: Page) => page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth);

function chain(tipo: string): { id: string; vigente: boolean; estado: string; motivo: string | null; serieId: string; anteriorId: string | null; deportista: string }[] {
  const out = dbQuery(
    "COPY (SELECT id, vigente, estado, motivo_observacion, serie_id, documento_anterior_id, coalesce(deportista_id::text, '-') FROM gestion_patin.documento WHERE tutor_id = :'tutor' AND tipo = :'tipo' ORDER BY creado_en, id) TO STDOUT;\n",
    { tutor: tutorId, tipo },
  );
  return out
    .split('\n')
    .filter((line) => line !== '')
    .map((line) => {
      const [id = '', vigente = '', estado = '', motivo = '\\N', serieId = '', anteriorId = '\\N', deportista = '-'] = line.split('\t');
      return { id, vigente: vigente === 't', estado, motivo: motivo === '\\N' ? null : motivo, serieId, anteriorId: anteriorId === '\\N' ? null : anteriorId, deportista };
    });
}
function stored(tipo: string): { key: string }[] {
  const out = dbQuery("COPY (SELECT storage_key FROM gestion_patin.documento WHERE tutor_id = :'tutor' AND tipo = :'tipo') TO STDOUT;\n", { tutor: tutorId, tipo });
  return out.split('\n').filter((l) => l !== '').map((key) => ({ key }));
}
const operations = (tipo: string) =>
  Number(dbQuery("COPY (SELECT count(*) FROM gestion_patin.operacion_carga_documental WHERE tutor_id = :'tutor' AND tipo = :'tipo') TO STDOUT;\n", { tutor: tutorId, tipo }).trim());
const audit = (documentId: string) =>
  dbQuery("COPY (SELECT accion, detalle::text FROM gestion_patin.auditoria WHERE recurso_tipo = 'DOCUMENTO' AND recurso_id = :'doc' ORDER BY id) TO STDOUT;\n", { doc: documentId })
    .split('\n').filter((l) => l !== '').map((l) => { const [action = '', detail = ''] = l.split('\t'); return { action, detail }; });

async function login(page: Page, who: SeededFamily): Promise<void> {
  await page.goto('/login');
  await page.getByLabel(/^Correo electrónico/).fill(who.email);
  await page.getByLabel(/^Contraseña/).fill(who.password);
  await page.getByRole('button', { name: 'Ingresar' }).click();
  await expect(page).toHaveURL(/\/familia$/);
}

test.beforeAll(async ({ browser }) => {
  if (OBJECTS_DIR === '') throw new Error('E2E_STORAGE_OBJECTS_DIR is not set: run .github/scripts/frontend-e2e.sh.');
  if (!hasDisposableDb()) throw new Error('E2E_DB_CONTAINER is not set: the chain and the stored objects are read through the disposable database.');
  browserRef = browser;
  adminContext = await browser.newContext({ baseURL: 'http://127.0.0.1:5173', viewport: { width: 1100, height: 900 } });
  await loginAdminUi(await adminContext.newPage());
  const seed = new AdminSeed(adminContext.request);
  const anonymous = await browser.newContext({ baseURL: 'http://127.0.0.1:5173' });
  const mk = (name: string, tag: string) => seed.createFamilyWithUser(anonymous.request, name, { nombre: 'Marta', apellido: 'Prueba', email: `tutor.${tag}.${suffix}@example.test`, password: `Fam-${randomBytes(8).toString('hex')}` });
  family = await mk(FAMILY_NAME, 'a');
  otherFamily = await mk(OTHER_FAMILY_NAME, 'b');
  await anonymous.close();
  const tutor = await seed.createTutor(family.familyId, { nombre: TUTOR_NAME, apellido: 'Sintético', parentesco: 'Madre' });
  tutorId = tutor.id;
  tutorDni = tutor.dni ?? '';
});

test.afterAll(async () => {
  await adminContext?.close();
});

test('U19: the family uploads a tutor DNI, the club approves one side and observes the other, the family replaces only the observed one and the club approves it', async () => {
  test.setTimeout(240_000);
  const objectsBefore = new Set(dbQuery("COPY (SELECT storage_key FROM gestion_patin.documento) TO STDOUT;\n", {}).split('\n'));
  const familyContext = await browserRef.newContext({ baseURL: 'http://127.0.0.1:5173', viewport: { width: 390, height: 844 } });
  const fam = await familyContext.newPage();
  const consoleLines: string[] = [];
  fam.on('console', (m) => consoleLines.push(m.text()));
  fam.on('pageerror', (e) => consoleLines.push(`pageerror: ${e.message}`));
  await login(fam, family);

  // 1. From the family card to the tutor's documents.
  await fam.getByRole('link', { name: `Documentos de ${TUTOR_NAME} Sintético` }).click();
  await expect(fam).toHaveURL(new RegExp(`/familia/tutores/${tutorId}$`));
  await expect(fam.getByRole('heading', { level: 1 })).toContainText(TUTOR_NAME);
  await expect(tutorSlot(fam, FRONT).getByText('Estado: Falta', { exact: true })).toBeVisible();
  expect(await noHorizontalOverflow(fam)).toBe(true);

  // 2. Upload front (photo bytes) and back (PDF) through the tutor route.
  const pdfBack = syntheticPdf();
  await tutorSlot(fam, FRONT).getByLabel('Elegir archivo para el DNI – frente', { exact: true }).setInputFiles({ name: 'frente.pdf', mimeType: 'application/pdf', buffer: syntheticPdf() });
  const up = (response: { url(): string; request(): { method(): string } }) => response.request().method() === 'POST' && new URL(response.url()).pathname === `/api/familia/tutores/${tutorId}/documentos`;
  const first = fam.waitForResponse(up);
  await tutorSlot(fam, FRONT).getByRole('button', { name: 'Enviar' }).click();
  expect((await first).status()).toBe(201);
  await tutorSlot(fam, BACK).getByLabel('Elegir archivo para el DNI – dorso', { exact: true }).setInputFiles({ name: 'dorso.pdf', mimeType: 'application/pdf', buffer: pdfBack });
  const second = fam.waitForResponse(up);
  await tutorSlot(fam, BACK).getByRole('button', { name: 'Enviar' }).click();
  expect((await second).status()).toBe(201);
  await expect(tutorSlot(fam, BACK).getByText('Estado: En revisión', { exact: true })).toBeVisible();
  const [front0] = chain('DNI_TUTOR_FRENTE');
  const [back0] = chain('DNI_TUTOR_DORSO');
  expect(front0).toMatchObject({ vigente: true, estado: 'CARGADO', deportista: '-' });
  expect(back0).toMatchObject({ vigente: true, estado: 'CARGADO', deportista: '-', serieId: back0!.id });

  // 3. The club reviews from the tutor detail: opens a document, approves the front and observes the back with a reason.
  const adm = await adminContext.newPage();
  await adm.goto(`/admin/familias/${family.familyId}/tutores/${tutorId}`);
  await expect(adm.getByRole('heading', { level: 1 })).toContainText(TUTOR_NAME);
  await expect(adminRow(adm, ADMIN_FRONT).getByText('Estado: Cargado', { exact: true })).toBeVisible();
  await adminRow(adm, ADMIN_BACK).getByRole('button', { name: `Abrir documento: ${ADMIN_BACK}` }).click();
  await expect(adm.getByText('PDF listo. Para verlo hay que descargarlo: queda una copia en este dispositivo.')).toBeVisible();
  await adminRow(adm, ADMIN_FRONT).getByRole('button', { name: `Aprobar documento: ${ADMIN_FRONT}` }).click();
  await adm.getByRole('dialog').getByRole('button', { name: 'Confirmar aprobación' }).click();
  await expect(adminRow(adm, ADMIN_FRONT)).toContainText('Aprobado');
  await adminRow(adm, ADMIN_BACK).getByRole('button', { name: `Observar documento: ${ADMIN_BACK}` }).click();
  await adm.getByRole('dialog').getByLabel('Motivo de la observación').fill(REASON);
  await adm.getByRole('dialog').getByRole('button', { name: 'Confirmar observación' }).click();
  await expect(adminRow(adm, ADMIN_BACK)).toContainText('Observado');

  // 4. The family sees the state and the reason, and can replace only the observed side.
  await fam.reload();
  await expect(tutorSlot(fam, FRONT).getByText('Estado: Aprobado', { exact: true })).toBeVisible();
  await expect(tutorSlot(fam, BACK).getByText('Estado: Observado', { exact: true })).toBeVisible();
  await expect(tutorSlot(fam, BACK).getByText(REASON)).toBeVisible();
  await expect(tutorSlot(fam, FRONT).getByLabel(/Sacar foto|Elegir archivo/)).toHaveCount(0);
  const pdfNew = syntheticPdf();
  await tutorSlot(fam, BACK).getByLabel('Elegir archivo para el DNI – dorso', { exact: true }).setInputFiles({ name: 'dorso-completo.pdf', mimeType: 'application/pdf', buffer: pdfNew });
  const rep = fam.waitForResponse((r) => r.request().method() === 'POST' && new URL(r.url()).pathname === `/api/familia/tutores/${tutorId}/documentos/${back0!.id}/reemplazo`);
  await tutorSlot(fam, BACK).getByRole('button', { name: 'Enviar' }).click();
  expect((await rep).status()).toBe(201);
  await expect(tutorSlot(fam, BACK).getByText('Estado: En revisión', { exact: true })).toBeVisible();

  // The chain: the successor inherits the series, the predecessor keeps its state and reason and is no longer current.
  const backChain = chain('DNI_TUTOR_DORSO');
  expect(backChain).toHaveLength(2);
  expect(backChain[0]).toMatchObject({ id: back0!.id, vigente: false, estado: 'OBSERVADO', motivo: REASON });
  expect(backChain[1]).toMatchObject({ vigente: true, estado: 'CARGADO', anteriorId: back0!.id, serieId: back0!.id });
  expect(operations('DNI_TUTOR_DORSO')).toBe(2);
  expect(operations('DNI_TUTOR_FRENTE')).toBe(1);
  expect(chain('DNI_TUTOR_FRENTE')).toHaveLength(1); // the approved side was never touched
  // The club opened the back in step 3 (an audited access) and then observed it.
  expect(audit(back0!.id).map((a) => a.action)).toEqual(['DOCUMENTO_CARGADO', 'DOCUMENTO_DESCARGADO', 'DOCUMENTO_REVISADO']);
  expect(audit(backChain[1]!.id).map((a) => a.action)).toEqual(['DOCUMENTO_REEMPLAZADO']);
  expect(audit(backChain[1]!.id)[0]!.detail).not.toContain(REASON);

  // 5. The club approves the new version and downloads it; the replaced one is gone from the API.
  await adm.reload();
  await expect(adminRow(adm, ADMIN_BACK)).toContainText('Cargado');
  await adminRow(adm, ADMIN_BACK).getByRole('button', { name: `Aprobar documento: ${ADMIN_BACK}` }).click();
  await adm.getByRole('dialog').getByRole('button', { name: 'Confirmar aprobación' }).click();
  await expect(adminRow(adm, ADMIN_BACK)).toContainText('Aprobado');
  const headers = await csrfHeaders(adminContext.request);
  const gone = await adminContext.request.get(`/api/admin/tutores/${tutorId}/documentos/${back0!.id}/archivo`, { headers });
  expect(gone.status()).toBe(404);
  const ok = await adminContext.request.get(`/api/admin/tutores/${tutorId}/documentos/${backChain[1]!.id}/archivo`, { headers });
  expect(ok.status()).toBe(200);
  expect(sha256(Buffer.from(await ok.body()))).toBe(sha256(pdfNew));
  expect(audit(backChain[1]!.id).map((a) => a.action)).toEqual(['DOCUMENTO_REEMPLAZADO', 'DOCUMENTO_REVISADO', 'DOCUMENTO_DESCARGADO']);
  expect(audit(backChain[1]!.id)[2]!.detail).toContain(tutorId);
  expect(stored('DNI_TUTOR_DORSO')).toHaveLength(2); // both objects are kept in Storage
  const newObjects = dbQuery("COPY (SELECT storage_key FROM gestion_patin.documento WHERE tutor_id = :'tutor') TO STDOUT;\n", { tutor: tutorId }).split('\n').filter((k) => k !== '' && !objectsBefore.has(k));
  expect(newObjects).toHaveLength(3);
  expect(readFileSync(join(OBJECTS_DIR, stored('DNI_TUTOR_DORSO')[1]!.key)).equals(pdfNew)).toBe(true);

  // 6. The family sees both sides approved, with nothing left to do.
  await fam.reload();
  await expect(tutorSlot(fam, FRONT).getByText('Estado: Aprobado', { exact: true })).toBeVisible();
  await expect(tutorSlot(fam, BACK).getByText('Estado: Aprobado', { exact: true })).toBeVisible();
  await expect(fam.getByRole('region', { name: 'Documentos del tutor' }).getByLabel(/Sacar foto|Elegir archivo/)).toHaveCount(0);
  await expect(fam.getByText(REASON)).toHaveCount(0);

  // Nothing personal in the address, the browser storage or the console.
  const personal = new RegExp([tutorDni, `${TUTOR_NAME} Sintético`.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')].join('|'));
  expect(fam.url()).not.toMatch(personal);
  expect(await fam.evaluate(() => JSON.stringify([{ ...localStorage }, { ...sessionStorage }]))).not.toMatch(new RegExp(tutorDni));
  expect(consoleLines.join('\n')).not.toMatch(new RegExp(tutorDni));
  expect(consoleLines.filter((l) => l.startsWith('pageerror:'))).toEqual([]);
  await familyContext.close();
});

test('U19: another family cannot reach the tutor documents, and no family can download a document', async () => {
  const stranger = await browserRef.newContext({ baseURL: 'http://127.0.0.1:5173' });
  const page = await stranger.newPage();
  await login(page, otherFamily);
  const headers = await csrfHeaders(stranger.request);
  const [doc] = chain('DNI_TUTOR_FRENTE');
  // The tutor of another family: the same 404 as a tutor that does not exist, for listing, upload and replacement.
  const list = await stranger.request.get(`/api/familia/tutores/${tutorId}/documentos`);
  expect(list.status()).toBe(404);
  const upload = await stranger.request.post(`/api/familia/tutores/${tutorId}/documentos`, {
    multipart: { tipo: 'DNI_TUTOR_FRENTE', archivo: { name: 'documento.pdf', mimeType: 'application/pdf', buffer: syntheticPdf() } },
    headers: { ...headers, 'Idempotency-Key': crypto.randomUUID() },
  });
  expect(upload.status()).toBe(404);
  const replace = await stranger.request.post(`/api/familia/tutores/${tutorId}/documentos/${doc!.id}/reemplazo`, {
    multipart: { archivo: { name: 'documento.pdf', mimeType: 'application/pdf', buffer: syntheticPdf() } },
    headers: { ...headers, 'Idempotency-Key': crypto.randomUUID() },
  });
  expect(replace.status()).toBe(404);
  expect(((await upload.json()) as { codigo: string }).codigo).toBe('TUTOR_NO_ENCONTRADO');
  // No family route serves a file, and the administrative ones refuse a family session.
  for (const path of [`/api/familia/tutores/${tutorId}/documentos/${doc!.id}/archivo`, `/api/admin/tutores/${tutorId}/documentos/${doc!.id}/archivo`, `/api/admin/tutores/${tutorId}/documentos`]) {
    expect([403, 404]).toContain((await stranger.request.get(path, { headers })).status());
  }
  expect(operations('DNI_TUTOR_FRENTE')).toBe(1); // the rejected requests left nothing behind
  await stranger.close();
});
