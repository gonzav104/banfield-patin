import { randomBytes } from 'node:crypto';
import { expect, test, type Browser, type BrowserContext, type Page } from '@playwright/test';
import { AdminSeed, loginAdminUi, type SeededFamily } from './support/seed';

/**
 * REAL flows of the responsables in the family sheet (C3, READ ONLY): browser -> Vite proxy -> Spring backend -> PostgreSQL, against
 * GET /api/familia/deportistas/{id}/responsables. Two families share ONE athlete: each must see only its own tutors, with a closed list of four
 * fields and no trace of the other. Requires the administrator enrolled by auth.real.spec.ts. Everything is synthetic and seeded through the
 * real admin API. The disposable E2E backend enables the alta flag only to seed (the product default stays disabled).
 */
test.describe.configure({ mode: 'serial' });

const suffix = randomBytes(3).toString('hex');
const password = () => `Fam-${randomBytes(8).toString('hex')}`;
const nm = (base: string) => `${base}${suffix}`;

let browserRef: Browser;
let adminContext: BrowserContext;
let seed: AdminSeed;
let familyA: SeededFamily;
let familyB: SeededFamily;
const ids = { shared: '', onlyA: '', onlyB: '', tutorA1: '', tutorA2: '', tutorB: '', responsableA2: '' };
const dnis: string[] = [];

const api = (id: string) => `/api/familia/deportistas/${id}/responsables`;
const noHorizontalOverflow = (page: Page) => page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth);

async function loginFamilyUi(page: Page, family: SeededFamily): Promise<void> {
  await page.goto('/login');
  await page.getByLabel(/^Correo electrónico/).fill(family.email);
  await page.getByLabel(/^Contraseña/).fill(family.password);
  await page.getByRole('button', { name: 'Ingresar' }).click();
  await expect(page).toHaveURL(/\/familia$/);
}

async function asFamily(family: SeededFamily): Promise<{ context: BrowserContext; page: Page }> {
  const context = await browserRef.newContext({ baseURL: 'http://127.0.0.1:5173' });
  const page = await context.newPage();
  await loginFamilyUi(page, family);
  return { context, page };
}

const section = (page: Page) => page.getByRole('region', { name: 'Responsables' });

test.beforeAll(async ({ browser }) => {
  browserRef = browser;
  adminContext = await browser.newContext({ baseURL: 'http://127.0.0.1:5173' });
  await loginAdminUi(await adminContext.newPage());
  seed = new AdminSeed(adminContext.request);
  const anonymous = await browser.newContext({ baseURL: 'http://127.0.0.1:5173' });

  familyA = await seed.createFamilyWithUser(anonymous.request, `E2E Resp Fam A ${suffix}`, { nombre: 'Lucía', apellido: 'Prueba', email: `resp.a.${suffix}@example.test`, password: password() });
  familyB = await seed.createFamilyWithUser(anonymous.request, `E2E Resp Fam B ${suffix}`, { nombre: 'Rosa', apellido: 'Prueba', email: `resp.b.${suffix}@example.test`, password: password() });

  ids.shared = (await seed.createAthlete({ nombre: nm('Compartido'), apellido: nm('Ambas') })).id;
  ids.onlyA = (await seed.createAthlete({ nombre: nm('SoloA'), apellido: nm('Propio') })).id;
  ids.onlyB = (await seed.createAthlete({ nombre: nm('SoloB'), apellido: nm('Ajeno') })).id;
  await seed.link(familyA.familyId, [ids.shared, ids.onlyA]);
  await seed.link(familyB.familyId, [ids.shared, ids.onlyB]);

  const a1 = await seed.createTutor(familyA.familyId, { nombre: nm('Ana'), apellido: nm('Alvarez'), telefono: '1155550001', email: `ana.${suffix}@example.test` });
  const a2 = await seed.createTutor(familyA.familyId, { nombre: nm('Beto'), apellido: nm('Benitez') });
  const b1 = await seed.createTutor(familyB.familyId, { nombre: nm('Intruso'), apellido: nm('Ajeno'), telefono: '1155550002' });
  const b2 = await seed.createTutor(familyB.familyId, { nombre: nm('Otra'), apellido: nm('Ajena') });
  ids.tutorA1 = a1.id;
  ids.tutorA2 = a2.id;
  ids.tutorB = b1.id;
  dnis.push(a1.dni!, a2.dni!, b1.dni!, b2.dni!);

  await seed.addResponsable(ids.shared, a1.id, familyA.familyId, 'MADRE');
  ids.responsableA2 = (await seed.addResponsable(ids.shared, a2.id, familyA.familyId)).id; // no type declared
  await seed.addResponsable(ids.shared, b1.id, familyB.familyId, 'PADRE');
  await seed.addResponsable(ids.shared, b2.id, familyB.familyId, 'TUTOR_LEGAL');
  await seed.addResponsable(ids.onlyB, b1.id, familyB.familyId, 'PADRE');
});

test.afterAll(async () => {
  await adminContext.close();
});

test('family A sees only its own tutors on a shared athlete, with plain-language types and no contacts', async () => {
  const { context, page } = await asFamily(familyA);
  await page.goto(`/familia/deportistas/${ids.shared}`);
  const box = section(page);
  const items = box.getByRole('listitem');
  await expect(items).toHaveCount(2);
  await expect(items.filter({ hasText: `Ana${suffix}` })).toContainText('Madre');
  const sinTipo = items.filter({ hasText: `Beto${suffix}` });
  await expect(sinTipo).toContainText('Sin declarar');
  await expect(sinTipo).not.toContainText('Sin determinar');
  await expect(box.getByText(/No acredita representación legal/)).toBeVisible();

  const text = await page.getByRole('main').innerText();
  expect(text).not.toMatch(new RegExp([`Intruso${suffix}`, `Ajen`, '1155550001', '1155550002', ...dnis].join('|')));
  await expect(box.getByRole('button')).toHaveCount(0);
  await expect(box.getByText(/historial/i)).toHaveCount(0);
  await context.close();
});

test('the raw answer is a closed list of four fields per responsable and never mentions the other family', async () => {
  const { context } = await asFamily(familyA);
  const response = await context.request.get(api(ids.shared));
  expect(response.status()).toBe(200);
  const raw = await response.text();
  const body = JSON.parse(raw) as { responsables: Record<string, unknown>[] };
  expect(Object.keys(body)).toEqual(['responsables']);
  expect(body.responsables).toHaveLength(2);
  body.responsables.forEach((r) => expect(Object.keys(r).sort()).toEqual(['apellido', 'nombre', 'tipo', 'tutorId']));
  expect(body.responsables.find((r) => r.tutorId === ids.responsableA2)).toBeUndefined(); // the id of a vigencia is never sent
  expect(body.responsables.some((r) => r.tipo === null)).toBe(true);
  expect(raw).not.toMatch(new RegExp([`Intruso${suffix}`, ids.tutorB, 'dni', 'telefono', 'email', 'version', 'historial', 'familiaId', 'escuelaId', ...dnis].join('|'), 'i'));
  await context.close();
});

test('family B sees only its own two on the same athlete, so neither count nor identity of the others is inferable', async () => {
  const { context, page } = await asFamily(familyB);
  await page.goto(`/familia/deportistas/${ids.shared}`);
  const items = section(page).getByRole('listitem');
  await expect(items).toHaveCount(2);
  await expect(items.filter({ hasText: `Intruso${suffix}` })).toContainText('Padre');
  await expect(items.filter({ hasText: `Otra${suffix}` })).toContainText('Tutor/a legal');
  expect(await page.getByRole('main').innerText()).not.toMatch(new RegExp([`Ana${suffix}`, `Beto${suffix}`, 'Madre'].join('|')));
  await context.close();
});

test('an athlete of the other family, or an unknown one, answers the same 404 as the sheet', async () => {
  const { context, page } = await asFamily(familyA);
  for (const id of [ids.onlyB, '00000000-0000-4000-8000-0000000000ff']) {
    const response = await context.request.get(api(id));
    expect(response.status()).toBe(404);
    expect(await response.json()).toMatchObject({ codigo: 'DEPORTISTA_NO_ENCONTRADO' });
  }
  await page.goto(`/familia/deportistas/${ids.onlyB}`);
  await expect(page.getByText('No encontramos este deportista')).toBeVisible();
  await expect(page.getByRole('region', { name: 'Responsables' })).toHaveCount(0);
  await context.close();
});

test('an own athlete without responsables is an empty list, not a 404', async () => {
  const { context, page } = await asFamily(familyA);
  const response = await context.request.get(api(ids.onlyA));
  expect(response.status()).toBe(200);
  expect(await response.json()).toEqual({ responsables: [] });
  await page.goto(`/familia/deportistas/${ids.onlyA}`);
  await expect(section(page).getByText(/Todavía no hay responsables registrados/)).toBeVisible();
  await context.close();
});

test('the family has no way to write: other methods are refused and no write is sent from the screen', async () => {
  const { context, page } = await asFamily(familyA);
  const writes: string[] = [];
  page.on('request', (request) => {
    if (request.method() !== 'GET' && /responsables|vinculos-tutores/.test(request.url())) writes.push(request.url());
  });
  await page.goto(`/familia/deportistas/${ids.shared}`);
  await expect(section(page).getByRole('listitem')).toHaveCount(2);
  const csrf = await context.request.get('/api/auth/csrf').then((r) => r.json() as Promise<{ headerName: string; token: string }>);
  for (const method of ['post', 'put', 'delete'] as const) {
    const response = await context.request[method](api(ids.shared), { headers: { [csrf.headerName]: csrf.token }, data: {} });
    expect([403, 405]).toContain(response.status());
  }
  expect((await context.request.get(`/api/admin/deportistas/${ids.shared}/vinculos-tutores`)).status()).toBe(403);
  expect(writes).toEqual([]);
  await context.close();
});

test('fits a phone screen, and a tutor deactivation closes the responsibility for good, even after reactivating the tutor', async () => {
  const { context, page } = await asFamily(familyA);
  await page.setViewportSize({ width: 360, height: 740 });
  await page.goto(`/familia/deportistas/${ids.shared}`);
  await expect(section(page).getByRole('listitem')).toHaveCount(2);
  expect(await noHorizontalOverflow(page)).toBe(true);

  await seed.deactivateTutor(ids.tutorA1);
  await page.reload();
  await expect(section(page).getByRole('listitem')).toHaveCount(1);
  await seed.activateTutor(ids.tutorA1);
  await page.reload();
  const items = section(page).getByRole('listitem');
  await expect(items).toHaveCount(1);
  await expect(items.first()).toContainText(`Beto${suffix}`);
  await context.close();
});

test('after the family link is revoked the sheet and the responsables answer the uniform 404', async () => {
  const { context, page } = await asFamily(familyA);
  await page.goto(`/familia/deportistas/${ids.shared}`);
  await expect(section(page).getByRole('listitem')).toHaveCount(1);
  await seed.revoke(familyA.familyId, ids.shared);
  expect((await context.request.get(api(ids.shared))).status()).toBe(404);
  await page.reload();
  await expect(page.getByText('No encontramos este deportista')).toBeVisible();
  // Family B keeps its own, unchanged by the revocation of A.
  const other = await asFamily(familyB);
  const body = (await (await other.context.request.get(api(ids.shared))).json()) as { responsables: unknown[] };
  expect(body.responsables).toHaveLength(2);
  await other.context.close();
  await context.close();
});
