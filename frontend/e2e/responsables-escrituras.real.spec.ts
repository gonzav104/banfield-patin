import { randomBytes, randomUUID } from 'node:crypto';
import { expect, test, type BrowserContext, type Page } from '@playwright/test';
import { AdminSeed, csrfHeaders, loginAdminUi } from './support/seed';

/**
 * REAL flows of the administrative writes of the responsables of an athlete (C3, second cut): browser -> Vite proxy -> Spring backend ->
 * PostgreSQL. Requires the administrator enrolled by auth.real.spec.ts. Everything is synthetic and seeded through the real admin API. The
 * disposable E2E backend runs with `banfield.responsables.alta-habilitada` ON in this shard (the product default stays disabled; the
 * flag-off case is covered by responsables-alta-deshabilitada.real.spec.ts in its own shard). Only the test labelled SIMULATED uses
 * page.route (a lost answer after the real commit cannot be produced on demand).
 */
test.describe.configure({ mode: 'serial' });

const suffix = randomBytes(3).toString('hex');
const nm = (base: string) => `${base}${suffix}`;

let adminContext: BrowserContext;
let page: Page;
let seed: AdminSeed;
const ids = { athlete: '', family: '', other: '', ana: '', beto: '', carla: '', ajena: '' };
const posts: { url: string; key: string | null }[] = [];

const path = () => `/admin/deportistas/${ids.athlete}/responsables`;
const api = () => `/api/admin/deportistas/${ids.athlete}/vinculos-tutores`;
const noHorizontalOverflow = () => page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth);
const current = () => page.getByRole('region', { name: 'Responsables vigentes' });
const history = () => page.getByRole('region', { name: 'Historial' });

interface Row { id: string; tutorId: string; tipo: string | null; cerradoEn: string | null; causaCierre: string | null; version: string }
async function rows(historial: boolean): Promise<Row[]> {
  const response = await adminContext.request.get(`${api()}?historial=${historial}&page=0&size=100`);
  expect(response.status()).toBe(200);
  return ((await response.json()) as { contenido: Row[] }).contenido;
}

async function openAlta() {
  await page.getByRole('button', { name: 'Agregar responsable' }).click();
  return page.getByRole('dialog', { name: 'Agregar responsable' });
}

test.beforeAll(async ({ browser }) => {
  adminContext = await browser.newContext({ baseURL: 'http://127.0.0.1:5173' });
  page = await adminContext.newPage();
  page.on('request', (request) => {
    if (request.method() === 'POST' && /vinculos-tutores$/.test(request.url())) posts.push({ url: request.url(), key: request.headers()['idempotency-key'] ?? null });
  });
  await loginAdminUi(page);
  seed = new AdminSeed(adminContext.request);

  ids.athlete = (await seed.createAthlete({ nombre: nm('Lucia'), apellido: nm('Escrituras') })).id;
  ids.family = (await seed.createFamily(`E2E Esc Uno ${suffix}`)).id;
  ids.other = (await seed.createFamily(`E2E Esc Dos ${suffix}`)).id;
  await seed.link(ids.family, [ids.athlete]);
  ids.ana = (await seed.createTutor(ids.family, { nombre: nm('Ana'), apellido: nm('Alvarez') })).id;
  ids.beto = (await seed.createTutor(ids.family, { nombre: nm('Beto'), apellido: nm('Benitez') })).id;
  ids.carla = (await seed.createTutor(ids.family, { nombre: nm('Carla'), apellido: nm('Campos') })).id;
  ids.ajena = (await seed.createTutor(ids.other, { nombre: nm('Ajena'), apellido: nm('Sinvinculo') })).id; // family WITHOUT a link to the athlete
});

test.afterAll(async () => {
  await adminContext.close();
});

test('the candidates are only the active tutors of the linked family, and the alta works from the keyboard with a type and ONE key', async () => {
  await seed.deactivateTutor(ids.carla); // inactive: never offered
  await page.goto(path());
  await expect(current().getByText('Este deportista no tiene responsables vigentes.')).toBeVisible();
  const add = page.getByRole('button', { name: 'Agregar responsable' });
  await add.focus();
  await page.keyboard.press('Enter');
  const dialog = page.getByRole('dialog', { name: 'Agregar responsable' });
  await expect(dialog.getByRole('radio')).toHaveCount(2);
  const names = (await dialog.getByRole('radio').evaluateAll((els) => els.map((e) => e.closest('label')?.textContent ?? ''))).join('\n');
  expect(names).toContain(`Ana${suffix}`);
  expect(names).toContain(`Beto${suffix}`);
  expect(names).not.toContain(`Carla${suffix}`);
  expect(names).not.toContain(`Ajena${suffix}`);
  await expect(dialog).not.toContainText(/\d{7,}/); // no full DNI anywhere in the dialog

  // Escape closes it and returns the focus to the button that opened it.
  await page.keyboard.press('Escape');
  await expect(dialog).toBeHidden();
  await expect(add).toBeFocused();

  const before = posts.length;
  await add.click();
  await dialog.getByRole('radio', { name: new RegExp(`Ana${suffix}`) }).check();
  await dialog.getByRole('button', { name: 'Continuar' }).click();
  await dialog.getByLabel('Tipo de responsable (opcional)').selectOption({ label: 'Madre' });
  await dialog.getByRole('button', { name: 'Confirmar alta' }).click();
  await expect(page.getByText('Responsable agregado.')).toBeVisible();
  expect(posts.length - before).toBe(1);
  expect(posts.at(-1)!.key).toMatch(/^[0-9a-f-]{36}$/);
  const item = current().getByRole('listitem').filter({ hasText: `Ana${suffix}` });
  await expect(item).toContainText('Madre');
  const saved = await rows(false);
  expect(saved).toHaveLength(1);
  expect(saved[0]).toMatchObject({ tutorId: ids.ana, tipo: 'MADRE' });
});

test('a double click on confirm sends exactly one request and creates exactly one responsable', async () => {
  const before = posts.length;
  await page.goto(path());
  const dialog = await openAlta();
  await dialog.getByRole('radio', { name: new RegExp(`Beto${suffix}`) }).check();
  await dialog.getByRole('button', { name: 'Continuar' }).click();
  await dialog.getByRole('button', { name: 'Confirmar alta' }).dblclick();
  await expect(page.getByText('Responsable agregado.')).toBeVisible();
  expect(posts.length - before).toBe(1);
  expect(await rows(false)).toHaveLength(2);
  const beto = (await rows(false)).find((r) => r.tutorId === ids.beto)!;
  expect(beto.tipo).toBeNull(); // not declared: null, never SIN_DETERMINAR
  await expect(current().getByRole('listitem').filter({ hasText: `Beto${suffix}` })).toContainText('Sin declarar');
});

test('the real server answers a repeated key with the original, rejects the same key with other content and never duplicates', async () => {
  const athleteId = (await seed.createAthlete({ nombre: nm('Dario'), apellido: nm('Idem') })).id;
  await seed.link(ids.family, [athleteId]);
  const tutorId = (await seed.createTutor(ids.family, { nombre: nm('Idem'), apellido: nm('Tutor') })).id;
  const get = async (url: string) => (await (await adminContext.request.get(url)).json()) as { version: string; versionVinculos?: string };
  const body = async (tipo?: string) => ({
    tutorId, ...(tipo ? { tipo } : {}),
    versionFamilia: (await get(`/api/admin/familias/${ids.family}`)).version,
    versionDeportista: (await get(`/api/admin/deportistas/${athleteId}`)).version,
    versionTutor: (await get(`/api/admin/tutores/${tutorId}`)).version,
    versionVinculos: (await get(`/api/admin/deportistas/${athleteId}/vinculos`)).versionVinculos,
  });
  const key = randomUUID();
  const send = async (data: unknown, k = key) => adminContext.request.post(`/api/admin/deportistas/${athleteId}/vinculos-tutores`, { data, headers: { ...(await csrfHeaders(adminContext.request)), 'Idempotency-Key': k } });
  const first = await send(await body('PADRE'));
  expect(first.status()).toBe(201);
  const created = (await first.json()) as { id: string };
  // The repetition carries revisions that are no longer the current ones: the original comes back, nothing is created.
  const again = await send(await body('PADRE'));
  expect(again.status()).toBe(201);
  expect(((await again.json()) as { id: string }).id).toBe(created.id);
  const other = await send(await body('MADRE'));
  expect(other.status()).toBe(409);
  expect(await other.json()).toMatchObject({ codigo: 'IDEMPOTENCY_KEY_REUTILIZADA' });
  const list = (await (await adminContext.request.get(`/api/admin/deportistas/${athleteId}/vinculos-tutores?historial=true&page=0&size=100`)).json()) as { contenido: unknown[] };
  expect(list.contenido).toHaveLength(1);
});

test('a stale decision creates nothing, asks to choose again and the new confirmation uses a NEW key', async () => {
  const before = posts.length;
  const extra = (await seed.createTutor(ids.family, { nombre: nm('Elena'), apellido: nm('Estale') })).id;
  await page.goto(path());
  const dialog = await openAlta();
  await dialog.getByRole('radio', { name: new RegExp(`Elena${suffix}`) }).check();
  await dialog.getByRole('button', { name: 'Continuar' }).click();
  const confirm = dialog.getByRole('button', { name: 'Confirmar alta' });
  await expect(confirm).toBeVisible();
  await seed.link(ids.other, [ids.athlete]); // another administrator moved the links of the athlete meanwhile
  await confirm.click();
  await expect(dialog.getByText(/Los datos cambiaron mientras decidías/)).toBeVisible();
  expect((await rows(false)).some((r) => r.tutorId === extra)).toBe(false);
  await dialog.getByRole('button', { name: 'Volver a elegir' }).click();
  await dialog.getByRole('radio', { name: new RegExp(`Elena${suffix}`) }).check();
  await dialog.getByRole('button', { name: 'Continuar' }).click();
  await dialog.getByRole('button', { name: 'Confirmar alta' }).click();
  await expect(page.getByText('Responsable agregado.')).toBeVisible();
  const keys = posts.slice(before).map((p) => p.key);
  expect(keys).toHaveLength(2);
  expect(keys[0]).not.toEqual(keys[1]);
  expect((await rows(false)).filter((r) => r.tutorId === extra)).toHaveLength(1);
});

test('SIMULATED lost answer after the real commit: the same key survives closing the dialog and retrying creates exactly one', async () => {
  const lone = (await seed.createTutor(ids.family, { nombre: nm('Flor'), apellido: nm('Perdida') })).id;
  await page.goto(path());
  let lost = true;
  await page.route(`**${api()}`, async (route) => {
    if (route.request().method() === 'POST' && lost) {
      lost = false;
      await route.fetch(); // the real server commits...
      await route.abort('connectionreset'); // ...and the answer never arrives
      return;
    }
    await route.continue();
  });
  const before = posts.length;
  let dialog = await openAlta();
  await dialog.getByRole('radio', { name: new RegExp(`Flor${suffix}`) }).check();
  await dialog.getByRole('button', { name: 'Continuar' }).click();
  await dialog.getByRole('button', { name: 'Confirmar alta' }).click();
  await expect(dialog.getByText(/No pudimos confirmar si el responsable se agregó/)).toBeVisible();
  expect((await rows(false)).filter((r) => r.tutorId === lone)).toHaveLength(1); // it really was created
  await dialog.getByRole('button', { name: 'Cancelar' }).click();

  dialog = await openAlta(); // straight to the recovery of the SAME request, no new choice
  await expect(dialog.getByRole('radio')).toHaveCount(0);
  await dialog.getByRole('button', { name: 'Reintentar la misma solicitud' }).click();
  await expect(page.getByText('Responsable agregado.')).toBeVisible();
  await page.unroute(`**${api()}`);
  const keys = posts.slice(before).map((p) => p.key);
  expect(keys).toHaveLength(2);
  expect(keys[0]).toBe(keys[1]);
  expect((await rows(false)).filter((r) => r.tutorId === lone)).toHaveLength(1);
});

test('the type is edited with its revision (null stays apart from SIN_DETERMINAR) and a revoked responsable is closed for good', async () => {
  await page.goto(path());
  const ana = current().getByRole('listitem').filter({ hasText: `Ana${suffix}` });
  await ana.getByRole('button', { name: /Cambiar tipo/ }).click();
  let dialog = page.getByRole('dialog', { name: 'Cambiar el tipo declarado' });
  await expect(dialog.getByRole('button', { name: 'Guardar tipo' })).toBeDisabled();
  await dialog.getByLabel('Tipo de responsable').selectOption({ label: 'Sin determinar' });
  await dialog.getByRole('button', { name: 'Guardar tipo' }).click();
  await expect(page.getByText('Tipo actualizado.')).toBeVisible();
  expect((await rows(false)).find((r) => r.tutorId === ids.ana)!.tipo).toBe('SIN_DETERMINAR');
  await ana.getByRole('button', { name: /Cambiar tipo/ }).click();
  dialog = page.getByRole('dialog', { name: 'Cambiar el tipo declarado' });
  await dialog.getByLabel('Tipo de responsable').selectOption({ label: 'Sin declarar' });
  await dialog.getByRole('button', { name: 'Guardar tipo' }).click();
  await expect(ana).toContainText('Sin declarar');
  expect((await rows(false)).find((r) => r.tutorId === ids.ana)!.tipo).toBeNull();

  await ana.getByRole('button', { name: /Revocar/ }).click();
  dialog = page.getByRole('dialog', { name: 'Revocar responsable' });
  await expect(dialog).toContainText(/irreversible/);
  await dialog.getByRole('button', { name: 'Cancelar' }).click();
  expect((await rows(false)).some((r) => r.tutorId === ids.ana)).toBe(true);
  await ana.getByRole('button', { name: /Revocar/ }).click();
  await page.getByRole('dialog', { name: 'Revocar responsable' }).getByRole('button', { name: 'Confirmar revocación' }).click();
  await expect(page.getByText('Responsable revocado.')).toBeVisible();
  await expect(current().getByRole('listitem').filter({ hasText: `Ana${suffix}` })).toHaveCount(0);
  await page.getByRole('button', { name: 'Ver historial' }).click();
  const closed = history().getByRole('listitem').filter({ hasText: `Ana${suffix}` });
  await expect(closed).toContainText('Revocada por el club');
  await expect(closed.getByRole('button')).toHaveCount(0);
  const raw = (await rows(true)).find((r) => r.tutorId === ids.ana)!;
  expect(raw.causaCierre).toBe('REVOCACION_ADMIN');
});

test('deactivating a tutor closes its responsibility for good and reactivating it never reopens it; a new confirmation is needed', async () => {
  await page.goto(`/admin/familias/${ids.family}/tutores/${ids.beto}`);
  await page.getByRole('button', { name: 'Desactivar tutor' }).click();
  const dialog = page.getByRole('dialog', { name: 'Desactivar tutor' });
  await expect(dialog).toContainText(/irreversible/);
  await dialog.getByRole('button', { name: 'Confirmar desactivación' }).click();
  await expect(page.getByText(/sus responsabilidades vigentes se cerraron/)).toBeVisible();
  await page.getByRole('button', { name: 'Reactivar tutor' }).click();
  await page.getByRole('dialog', { name: 'Reactivar tutor' }).getByRole('button', { name: 'Confirmar reactivación' }).click();
  await expect(page.getByRole('button', { name: 'Desactivar tutor' })).toBeVisible();

  const all = await rows(true);
  const beto = all.filter((r) => r.tutorId === ids.beto);
  expect(beto).toHaveLength(1);
  expect(beto[0]).toMatchObject({ causaCierre: 'TUTOR_INACTIVO' });
  expect((await rows(false)).some((r) => r.tutorId === ids.beto)).toBe(false);

  await page.goto(path()); // eligible again, but only through a new explicit confirmation
  const alta = await openAlta();
  await alta.getByRole('radio', { name: new RegExp(`Beto${suffix}`) }).check();
  await alta.getByRole('button', { name: 'Continuar' }).click();
  await alta.getByRole('button', { name: 'Confirmar alta' }).click();
  await expect(page.getByText('Responsable agregado.')).toBeVisible();
  expect((await rows(true)).filter((r) => r.tutorId === ids.beto)).toHaveLength(2);
});

test('the dialogs fit a 360 px phone and keep every control reachable by keyboard', async () => {
  await page.setViewportSize({ width: 360, height: 740 });
  await page.goto(path());
  expect(await noHorizontalOverflow()).toBe(true);
  const dialog = await openAlta();
  await expect(dialog).toBeVisible();
  expect(await noHorizontalOverflow()).toBe(true);
  const box = await dialog.boundingBox();
  expect(box!.x).toBeGreaterThanOrEqual(0);
  expect(box!.x + box!.width).toBeLessThanOrEqual(360);
  await page.keyboard.press('Tab');
  expect(await page.evaluate(() => document.activeElement?.closest('dialog') !== null)).toBe(true); // focus never leaves the dialog
  await page.keyboard.press('Escape');
  await expect(dialog).toBeHidden();
});
