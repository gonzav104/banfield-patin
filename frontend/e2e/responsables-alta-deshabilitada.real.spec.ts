import { randomBytes, randomUUID } from 'node:crypto';
import { expect, test, type BrowserContext, type Page } from '@playwright/test';
import { AdminSeed, csrfHeaders, loginAdminUi } from './support/seed';

/**
 * The alta of responsables with the flag OFF, as in production (`banfield.responsables.alta-habilitada=false`): this file runs in its own
 * shard, whose disposable backend starts with the flag off (E2E_ALTA_RESPONSABLES=false). The server must refuse the alta before reading
 * or writing anything, the screen must say so without alarm and create nothing, and the read screens must keep working.
 */
test.describe.configure({ mode: 'serial' });

const suffix = randomBytes(3).toString('hex');
const nm = (base: string) => `${base}${suffix}`;

let adminContext: BrowserContext;
let page: Page;
let athleteId = '';
let familyId = '';
let tutorId = '';

const api = () => `/api/admin/deportistas/${athleteId}/vinculos-tutores`;

test.beforeAll(async ({ browser }) => {
  adminContext = await browser.newContext({ baseURL: 'http://127.0.0.1:5173' });
  page = await adminContext.newPage();
  await loginAdminUi(page);
  const seed = new AdminSeed(adminContext.request);
  athleteId = (await seed.createAthlete({ nombre: nm('Lucia'), apellido: nm('Apagada') })).id;
  familyId = (await seed.createFamily(`E2E Apagada ${suffix}`)).id;
  await seed.link(familyId, [athleteId]);
  tutorId = (await seed.createTutor(familyId, { nombre: nm('Ana'), apellido: nm('Alvarez') })).id;
});

test.afterAll(async () => {
  await adminContext.close();
});

test('the real server refuses the alta with 503 before checking anything else, and creates nothing', async () => {
  const response = await adminContext.request.post(api(), {
    data: { tutorId, versionFamilia: '0', versionDeportista: '0', versionTutor: '0', versionVinculos: '0' },
    headers: { ...(await csrfHeaders(adminContext.request)), 'Idempotency-Key': randomUUID() },
  });
  expect(response.status()).toBe(503);
  expect(await response.json()).toMatchObject({ codigo: 'ALTA_RESPONSABLES_DESHABILITADA' });
  const list = (await (await adminContext.request.get(`${api()}?historial=true&page=0&size=20`)).json()) as { contenido: unknown[] };
  expect(list.contenido).toHaveLength(0);
});

test('the screen reads normally, the alta is refused with an informational message and nothing is created or retried', async () => {
  const posts: string[] = [];
  page.on('request', (request) => {
    if (request.method() === 'POST' && /vinculos-tutores$/.test(request.url())) posts.push(request.headers()['idempotency-key'] ?? '');
  });
  await page.goto(`/admin/deportistas/${athleteId}/responsables`);
  await expect(page.getByText('Este deportista no tiene responsables vigentes.')).toBeVisible();
  await page.getByRole('button', { name: 'Agregar responsable' }).click();
  const dialog = page.getByRole('dialog', { name: 'Agregar responsable' });
  await dialog.getByRole('radio', { name: new RegExp(`Ana${suffix}`) }).check();
  await dialog.getByRole('button', { name: 'Continuar' }).click();
  await dialog.getByRole('button', { name: 'Confirmar alta' }).click();
  await expect(dialog.getByText(/no está habilitada por el momento/)).toBeVisible();
  await expect(dialog.getByRole('button', { name: 'Reintentar la misma solicitud' })).toHaveCount(0);
  expect(posts).toHaveLength(1);
  await dialog.getByRole('button', { name: 'Cancelar' }).click();
  await page.reload();
  await expect(page.getByText('Este deportista no tiene responsables vigentes.')).toBeVisible();
  const list = (await (await adminContext.request.get(`${api()}?historial=true&page=0&size=20`)).json()) as { contenido: unknown[] };
  expect(list.contenido).toHaveLength(0);
});
