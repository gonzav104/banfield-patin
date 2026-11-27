import { randomBytes } from 'node:crypto';
import { expect, test, type BrowserContext, type Page } from '@playwright/test';
import { dbQuery, hasDisposableDb } from './support/db';
import { AdminSeed, csrfHeaders, loginAdminUi, schoolToday, type SeededFamily } from './support/seed';

/**
 * The apto físico with the upload flag OFF, as in production (`DOCUMENTOS_CARGA_HABILITADA=false`): this file runs in the shard whose
 * disposable backend starts with the flag off (E2E_CARGA_DOCUMENTAL=false). The server must refuse every upload, the family screen must say
 * so without alarm and offer no way to send a file, and the read screens (family and ADMIN) must keep working.
 */
test.describe.configure({ mode: 'serial' });
// Outside CI this file skips when the upload is on (a full local run); IN CI it never skips: a shard 3 that lost its wiring must fail, not report green.
test.skip(process.env.E2E_CARGA_DOCUMENTAL !== 'false' && !process.env.CI, 'Runs only in the shard that starts the backend with the upload OFF (E2E_CARGA_DOCUMENTAL=false).');

const suffix = randomBytes(3).toString('hex');
const YEAR = Number(schoolToday().slice(0, 4));
const ATHLETE = `E2E-AptoOff-${suffix}`;
let adminContext: BrowserContext;
let adminPage: Page;
let family: SeededFamily;
let athleteId = '';
let seasonId = '';

test.beforeAll(async ({ browser }) => {
  if (process.env.E2E_CARGA_DOCUMENTAL !== 'false') throw new Error('E2E_CARGA_DOCUMENTAL must be false for this file: the shard 3 wiring (script + workflow) is missing.');
  if (!hasDisposableDb()) throw new Error('E2E_DB_CONTAINER is not set.');
  adminContext = await browser.newContext({ baseURL: 'http://127.0.0.1:5173' });
  adminPage = await adminContext.newPage();
  await loginAdminUi(adminPage);
  const seed = new AdminSeed(adminContext.request);
  const anonymous = await browser.newContext({ baseURL: 'http://127.0.0.1:5173' });
  family = await seed.createFamilyWithUser(anonymous.request, `E2E AptoOff ${suffix}`, { nombre: 'Marta', apellido: 'Prueba', email: `apto.off.${suffix}@example.test`, password: `Fam-${randomBytes(8).toString('hex')}` });
  await anonymous.close();
  athleteId = (await seed.createAthlete({ nombre: ATHLETE, apellido: 'Sintetico' })).id;
  await seed.link(family.familyId, [athleteId]);
  const season = await seed.season(YEAR);
  seasonId = season.id;
  // The portal lists an athlete's seasons only up to the ACTIVE one (a family never sees a season after it): with no active season the list is empty.
  if (!season.activa) await seed.activateSeason(season.id);
  await seed.addSportsRow(athleteId, seasonId, { categoria: 'Sub-11-Off', rama: 'Femenina', divisional: 'Off A', numeroCorredor: '0917' });
});

test.afterAll(async () => {
  await adminContext?.close();
});

test('the server refuses the upload with 503 before anything is stored, and the family screen offers no way to send a file', async ({ browser }) => {
  const context = await browser.newContext({ baseURL: 'http://127.0.0.1:5173' });
  const page = await context.newPage();
  await page.goto('/login');
  await page.getByLabel(/^Correo electrónico/).fill(family.email);
  await page.getByLabel(/^Contraseña/).fill(family.password);
  await page.getByRole('button', { name: 'Ingresar' }).click();
  await expect(page).toHaveURL(/\/familia$/);

  expect(await (await page.request.get('/api/familia/documentos/disponibilidad')).json()).toMatchObject({ habilitada: false });
  await page.goto(`/familia/deportistas/${athleteId}`);
  const region = page.getByRole('region', { name: 'Apto físico' });
  await expect(region.getByText('Falta')).toBeVisible();
  await expect(region.getByText('La carga de documentos no está habilitada por el momento.')).toBeVisible();
  await expect(region.getByLabel(/Sacar foto/)).toHaveCount(0);
  await expect(region.getByRole('button', { name: 'Enviar' })).toHaveCount(0);

  const response = await page.request.post(`/api/familia/deportistas/${athleteId}/documentos`, {
    headers: { ...(await csrfHeaders(page.request)), 'Idempotency-Key': crypto.randomUUID() },
    multipart: {
      tipo: 'APTO_FISICO',
      temporadaId: seasonId,
      fechaEmision: `${YEAR}-03-01`,
      medicoNombre: 'Dra E2E Apagada',
      medicoMatricula: 'MP-OFF-1',
      archivo: { name: 'documento.jpg', mimeType: 'image/jpeg', buffer: Buffer.from([0xff, 0xd8, 0xff, 0xe0, 1, 2, 3, 0xff, 0xd9]) },
    },
  });
  expect(response.status()).toBe(503);
  expect(await response.json()).toMatchObject({ codigo: 'CARGA_NO_DISPONIBLE' });
  const stored = dbQuery("COPY (SELECT (SELECT count(*) FROM gestion_patin.documento WHERE deportista_id = :'athlete'), (SELECT count(*) FROM gestion_patin.operacion_carga_documental WHERE deportista_id = :'athlete')) TO STDOUT;\n", { athlete: athleteId });
  expect(stored.trim()).toBe('0\t0');
  await context.close();
});

test('ADMIN keeps reading the apto of the season while the upload is off', async () => {
  await adminPage.goto(`/admin/deportistas/${athleteId}`);
  const region = adminPage.getByRole('region', { name: 'Apto físico' });
  await expect(region.getByText('Falta')).toBeVisible();
  await expect(region.getByText(`Vigente hasta el 31 de diciembre de ${YEAR}.`)).toBeVisible();
  await expect(region.getByRole('button', { name: /Aprobar|Observar|Abrir|Corregir/ })).toHaveCount(0);
});
