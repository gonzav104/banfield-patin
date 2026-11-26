import { randomBytes } from 'node:crypto';
import { expect, test, type BrowserContext, type Page } from '@playwright/test';
import { AdminSeed, loginAdminUi } from './support/seed';

/**
 * REAL flows of the administrative consultation of the responsables of an athlete (C3, READ ONLY): browser -> Vite proxy -> Spring backend ->
 * PostgreSQL, against GET /api/admin/deportistas/{id}/vinculos-tutores. Requires the administrator enrolled by auth.real.spec.ts. Everything is
 * synthetic and created through the real admin API (AdminSeed). The disposable E2E backend enables `banfield.responsables.alta-habilitada` only
 * to seed (the product default stays disabled). The screen is strictly read-only: the file asserts that no write reaches vinculos-tutores.
 */
test.describe.configure({ mode: 'serial' });

const suffix = randomBytes(3).toString('hex');
const name = (base: string) => `${base}${suffix}`;

let adminContext: BrowserContext;
let page: Page;
let seed: AdminSeed;
const dni: string[] = [];
const apiWrites: string[] = [];
const ids = { athlete: '', empty: '' };

const path = (id: string) => `/admin/deportistas/${id}/responsables`;
const noHorizontalOverflow = () => page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth);
const current = () => page.getByRole('region', { name: 'Responsables vigentes' });
const history = () => page.getByRole('region', { name: 'Historial' });

test.beforeAll(async ({ browser }) => {
  adminContext = await browser.newContext({ baseURL: 'http://127.0.0.1:5173' });
  page = await adminContext.newPage();
  page.on('request', (request) => {
    if (request.method() !== 'GET' && /vinculos-tutores/.test(request.url())) apiWrites.push(`${request.method()} ${request.url()}`);
  });
  await loginAdminUi(page);
  seed = new AdminSeed(adminContext.request);

  const athlete = await seed.createAthlete({ nombre: name('Lucia'), apellido: name('Responsables') });
  ids.athlete = athlete.id;
  ids.empty = (await seed.createAthlete({ nombre: name('Dario'), apellido: name('SinResp') })).id;
  const one = await seed.createFamily(`E2E Resp Uno ${suffix}`);
  const two = await seed.createFamily(`E2E Resp Dos ${suffix}`);
  await seed.link(one.id, [athlete.id]);
  await seed.link(two.id, [athlete.id]);

  const madre = await seed.createTutor(one.id, { nombre: name('Ana'), apellido: name('Alvarez') });
  const sinTipo = await seed.createTutor(one.id, { nombre: name('Beto'), apellido: name('Benitez') });
  const revocado = await seed.createTutor(one.id, { nombre: name('Carla'), apellido: name('Campos') });
  const baja = await seed.createTutor(two.id, { nombre: name('Dora'), apellido: name('Duarte') });
  const otraFamilia = await seed.createTutor(two.id, { nombre: name('Esteban'), apellido: name('Estevez') });
  dni.push(madre.dni!, sinTipo.dni!, revocado.dni!, baja.dni!, otraFamilia.dni!);

  await seed.addResponsable(athlete.id, madre.id, one.id, 'MADRE');
  await seed.addResponsable(athlete.id, sinTipo.id, one.id); // no type declared: null, not SIN_DETERMINAR
  const toRevoke = await seed.addResponsable(athlete.id, revocado.id, one.id, 'SIN_DETERMINAR');
  await seed.addResponsable(athlete.id, baja.id, two.id, 'TUTOR_LEGAL');
  await seed.addResponsable(athlete.id, otraFamilia.id, two.id, 'OTRO_RESPONSABLE');
  await seed.revokeResponsable(athlete.id, toRevoke.id);
  await seed.deactivateTutor(baja.id); // closes its responsibility; reactivating the tutor must not reopen it
  await seed.activateTutor(baja.id);
});

test.afterAll(async () => {
  await adminContext.close();
});

test('the athlete detail leads to the responsables, which list the open ones with plain-language types', async () => {
  await page.goto(`/admin/deportistas/${ids.athlete}`);
  await page.getByRole('link', { name: 'Ver responsables' }).click();
  await expect(page).toHaveURL(new RegExp(`${path(ids.athlete)}$`));
  const title = page.getByRole('heading', { level: 1, name: 'Responsables' });
  await expect(title).toBeFocused();

  const items = current().getByRole('listitem');
  await expect(items).toHaveCount(3);
  const text = (await items.allInnerTexts()).join('\n');
  expect(text).toContain(`Ana${suffix} Alvarez${suffix}`);
  expect(text).toContain('Madre');
  expect(text).toContain('Otro responsable');
  const sinTipo = items.filter({ hasText: `Beto${suffix}` });
  await expect(sinTipo).toContainText('Sin declarar');
  await expect(sinTipo).not.toContainText('Sin determinar');
  // Closed ones never appear among the open ones.
  expect(text).not.toContain(`Carla${suffix}`);
  expect(text).not.toContain(`Dora${suffix}`);
  await expect(page.getByText(/no acredita representación legal/)).toBeVisible();
});

test('the history is requested only on demand and tells why each responsibility ended, even after the tutor is active again', async () => {
  await page.goto(path(ids.athlete));
  await expect(current().getByRole('listitem')).toHaveCount(3);
  const toggle = page.getByRole('button', { name: 'Ver historial' });
  await expect(toggle).toHaveAttribute('aria-expanded', 'false');
  await toggle.focus();
  await page.keyboard.press('Enter');
  await expect(page.getByRole('button', { name: 'Ocultar historial' })).toHaveAttribute('aria-expanded', 'true');

  const rows = history().getByRole('listitem');
  await expect(rows).toHaveCount(5);
  const revoked = rows.filter({ hasText: `Carla${suffix}` });
  await expect(revoked).toContainText('Cerrada');
  await expect(revoked).toContainText('Revocada por el club');
  await expect(revoked).toContainText('Sin determinar');
  const deactivated = rows.filter({ hasText: `Dora${suffix}` });
  await expect(deactivated).toContainText('El tutor se desactivó');
  await expect(rows.filter({ hasText: `Ana${suffix}` })).toContainText('Vigente');
  await expect(history().getByText(/no se reabre/)).toBeVisible();

  await page.getByRole('button', { name: 'Ocultar historial' }).click();
  await expect(rows).toHaveCount(0);
});

test('an athlete without responsables shows both empty states, and an unknown athlete is one uniform «not found»', async () => {
  await page.goto(path(ids.empty));
  await expect(page.getByText('Este deportista no tiene responsables vigentes.')).toBeVisible();
  await page.getByRole('button', { name: 'Ver historial' }).click();
  await expect(page.getByText('Todavía no hay responsabilidades cerradas para este deportista.')).toBeVisible();

  await page.goto(path('11111111-1111-4111-8111-111111111111'));
  await expect(page.getByText(/No encontramos este deportista|no existe/i).first()).toBeVisible();
  await expect(page.getByRole('region', { name: 'Responsables vigentes' })).toHaveCount(0);
});

test('keeps the layout usable on a phone and never exposes personal data or sends a write', async () => {
  await page.setViewportSize({ width: 360, height: 740 });
  await page.goto(path(ids.athlete));
  await expect(current().getByRole('listitem')).toHaveCount(3);
  expect(await noHorizontalOverflow()).toBe(true);
  await page.getByRole('button', { name: 'Ver historial' }).click();
  await expect(history().getByRole('listitem')).toHaveCount(5);
  expect(await noHorizontalOverflow()).toBe(true);

  const body = await page.getByRole('main').innerText();
  for (const number of dni) expect(body).not.toContain(number);
  expect(apiWrites).toEqual([]);
});
