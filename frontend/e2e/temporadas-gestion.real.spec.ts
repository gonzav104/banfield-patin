import { expect, test, type BrowserContext, type Page } from '@playwright/test';
import { AdminSeed, loginAdminUi } from './support/seed';

// REAL browser -> backend -> disposable PostgreSQL. Only the explicitly labelled lost-response scenario intercepts transport (and it still
// lets the real backend apply the write). The school's seasons are shared by every spec of the shard, so this one only asserts on the
// years it owns (2061-2069) and on the REAL state read back through the API.
test.describe.configure({ mode: 'serial' });
const YEARS = { created: 2061, second: 2062, stale: 2063, other: 2064, idempotent: 2065, lost: 2066, double: 2067, layout: 2068 } as const;
let context: BrowserContext;
let page: Page;
let seed: AdminSeed;
const posts: { url: string; body: unknown }[] = [];
const modal = () => page.getByRole('dialog');
const activate = (year: number) => page.getByRole('button', { name: `Activar la temporada ${year}` });
const row = (year: number) => page.getByRole('listitem').filter({ hasText: String(year) });
const activations = () => posts.filter((p) => p.url.endsWith('/activar'));

type ApiSeason = { id: string; anio: number; activa: boolean };
async function seasons(): Promise<ApiSeason[]> {
  const response = await context.request.get('/api/admin/temporadas?page=0&size=100');
  expect(response.status()).toBe(200);
  return (await response.json() as { contenido: ApiSeason[] }).contenido;
}
const activeNow = async () => (await seasons()).find((s) => s.activa) ?? null;
const byYear = async (year: number) => (await seasons()).find((s) => s.anio === year)!;

test.beforeAll(async ({ browser }) => {
  context = await browser.newContext({ baseURL: 'http://127.0.0.1:5173' });
  page = await context.newPage();
  await loginAdminUi(page);
  seed = new AdminSeed(context.request);
  page.on('request', (request) => {
    if (request.method() === 'POST' && new URL(request.url()).pathname.startsWith('/api/admin/temporadas')) {
      posts.push({ url: new URL(request.url()).pathname, body: request.postDataJSON() });
    }
  });
});
test.afterAll(async () => { await context.close(); });

test('the section is reachable from the ADMIN navigation and lists the seasons with their state as text', async () => {
  await page.goto('/admin');
  await page.getByRole('navigation', { name: 'Principal' }).getByRole('link', { name: 'Temporadas' }).click();
  await expect(page).toHaveURL(/\/admin\/temporadas$/);
  await expect(page.getByRole('heading', { level: 1, name: 'Temporadas' })).toBeVisible();
  await expect(page.getByLabel('Año de la temporada')).toBeVisible();
  const active = await activeNow();
  if (active) await expect(page.getByRole('list', { name: 'Temporada activa' })).toContainText(String(active.anio));
  else await expect(page.getByText(/Ninguna temporada está activa/)).toBeVisible();
});

test('creates an inactive season with only the year, rejects bad years without sending and tells a duplicate', async () => {
  await page.goto('/admin/temporadas');
  const input = page.getByLabel('Año de la temporada');
  const submit = page.getByRole('button', { name: 'Crear temporada' });
  const before = posts.length;
  for (const [typed, message] of [['2019', 'El año debe estar entre 2020 y 2100.'], ['2101', 'El año debe estar entre 2020 y 2100.'], ['2061.5', 'Ingresá un año entero, solo con números.'], ['1e3', 'Ingresá un año entero, solo con números.']] as const) {
    await input.fill(typed);
    await submit.click();
    await expect(page.getByText(message, { exact: true })).toBeVisible();
  }
  expect(posts.length).toBe(before);
  await input.fill(String(YEARS.created));
  await submit.click();
  await expect(page.getByText(`Temporada ${YEARS.created} creada. Queda inactiva hasta que la actives.`)).toBeVisible();
  expect(posts.at(-1)).toMatchObject({ url: '/api/admin/temporadas', body: { anio: YEARS.created } });
  expect(await byYear(YEARS.created)).toMatchObject({ anio: YEARS.created, activa: false });
  await expect(row(YEARS.created)).toContainText('Inactiva');
  await input.fill(String(YEARS.created));
  await submit.click();
  await expect(page.getByText('Ya existe una temporada de ese año.')).toBeVisible();
  await expect(input).toHaveValue(String(YEARS.created));
  expect((await seasons()).filter((s) => s.anio === YEARS.created)).toHaveLength(1);
});

test('the confirmation explains the consequences, cancel and Escape do not write, the modal traps and restores focus', async () => {
  await page.goto('/admin/temporadas');
  const trigger = activate(YEARS.created);
  await expect(trigger).toBeEnabled();
  const before = activations().length;
  for (const close of ['cancel', 'escape'] as const) {
    await trigger.click();
    await expect(modal()).toHaveAccessibleName(`Activar la temporada ${YEARS.created}`);
    await expect(modal()).toHaveAttribute('aria-describedby');
    await expect(modal()).toContainText(`Se activará la temporada ${YEARS.created}.`);
    await expect(modal()).toContainText('no se eliminan ni se trasladan');
    const cancel = modal().getByRole('button', { name: 'Cancelar' });
    await expect(cancel).toBeFocused();
    await page.keyboard.press('Tab');
    await expect(modal().getByRole('button', { name: 'Confirmar activación' })).toBeFocused();
    await page.keyboard.press('Shift+Tab');
    await expect(cancel).toBeFocused();
    if (close === 'cancel') await cancel.click(); else await page.keyboard.press('Escape');
    await expect(modal()).toHaveCount(0);
    await expect(trigger).toBeFocused();
  }
  expect(activations().length).toBe(before);
});

test('activates sending the OBSERVED active season as the intent, and the previous one stops being active', async () => {
  await seed.season(YEARS.second);
  const observed = await activeNow();
  await page.goto('/admin/temporadas');
  await activate(YEARS.created).click();
  await expect(modal()).toContainText(observed ? `Dejará de estar activa la temporada ${observed.anio}.` : 'Ahora ninguna temporada está activa.');
  const request = page.waitForRequest((r) => r.method() === 'POST' && r.url().endsWith('/activar'));
  await modal().getByRole('button', { name: 'Confirmar activación' }).click();
  expect((await request).postDataJSON()).toEqual({ activaEsperadaId: observed?.id ?? null });
  await expect(page.getByText(`La temporada ${YEARS.created} es la activa.`)).toBeVisible();
  expect((await seasons()).filter((s) => s.activa).map((s) => s.anio)).toEqual([YEARS.created]);
  await expect(page.getByRole('list', { name: 'Temporada activa' })).toContainText(String(YEARS.created));
  if (observed) await expect(row(observed.anio)).toContainText('Inactiva'); // historical seasons stay listed, nothing is removed
});

test('replaces the active season and keeps the old one as a normal inactive season', async () => {
  await page.goto('/admin/temporadas');
  await activate(YEARS.second).click();
  await expect(modal()).toContainText(`Dejará de estar activa la temporada ${YEARS.created}.`);
  await modal().getByRole('button', { name: 'Confirmar activación' }).click();
  await expect(page.getByText(`La temporada ${YEARS.second} es la activa.`)).toBeVisible();
  expect((await seasons()).filter((s) => s.activa).map((s) => s.anio)).toEqual([YEARS.second]);
  await expect(row(YEARS.created)).toContainText('Inactiva');
  await expect(activate(YEARS.created)).toBeEnabled();
});

test('a stale intent (another administrator activated a season) is rejected by the real backend, then reconfirmed explicitly', async () => {
  const target = await seed.season(YEARS.stale);
  const other = await seed.season(YEARS.other);
  await page.goto('/admin/temporadas');
  await activate(YEARS.stale).click();
  await expect(modal()).toContainText(`Dejará de estar activa la temporada ${YEARS.second}.`);
  await seed.activateSeason(other.id); // a REAL concurrent write by another administrator
  const before = activations().length;
  await modal().getByRole('button', { name: 'Confirmar activación' }).click();
  await expect(modal()).toContainText('La temporada activa cambió mientras decidías');
  expect(activations().length).toBe(before + 1);
  expect(activations().at(-1)?.body).toEqual({ activaEsperadaId: (await byYear(YEARS.second)).id });
  expect((await seasons()).filter((s) => s.activa).map((s) => s.anio)).toEqual([YEARS.other]);
  await expect(modal().getByRole('button', { name: 'Confirmar activación' })).toHaveCount(0);
  await modal().getByRole('button', { name: 'Consultar temporadas vigentes' }).click();
  await expect(modal()).toContainText('no confirma el resultado de la petición anterior');
  expect(activations().length).toBe(before + 1); // the consultation never writes
  await modal().getByRole('button', { name: 'Revisar nueva confirmación' }).click();
  await expect(modal()).toContainText(`Dejará de estar activa la temporada ${YEARS.other}.`);
  await modal().getByRole('button', { name: 'Confirmar activación' }).click();
  await expect(page.getByText(`La temporada ${YEARS.stale} es la activa.`)).toBeVisible();
  expect(activations().at(-1)?.body).toEqual({ activaEsperadaId: other.id });
  expect((await seasons()).filter((s) => s.activa).map((s) => s.id)).toEqual([target.id]);
});

test('asking for the season that already became the active one is idempotent: success, nothing else changes', async () => {
  const target = await seed.season(YEARS.idempotent);
  await page.goto('/admin/temporadas');
  await activate(YEARS.idempotent).click();
  await seed.activateSeason(target.id);
  await modal().getByRole('button', { name: 'Confirmar activación' }).click();
  await expect(page.getByText(`La temporada ${YEARS.idempotent} es la activa.`)).toBeVisible();
  expect((await seasons()).filter((s) => s.activa).map((s) => s.id)).toEqual([target.id]);
});

test('a double click sends the activation once', async () => {
  await seed.season(YEARS.double);
  await page.goto('/admin/temporadas');
  await activate(YEARS.double).click();
  const before = activations().length;
  await modal().getByRole('button', { name: 'Confirmar activación' }).dblclick();
  await expect(page.getByText(`La temporada ${YEARS.double} es la activa.`)).toBeVisible();
  expect(activations().length).toBe(before + 1);
});

test('SIMULATED lost response: the backend applies the activation but the answer never arrives; the screen claims nothing, never retries and asks to consult', async () => {
  const target = await seed.season(YEARS.lost);
  await page.goto('/admin/temporadas');
  await activate(YEARS.lost).click();
  let attempts = 0;
  await page.route(`**/api/admin/temporadas/${target.id}/activar`, async (route) => {
    attempts += 1;
    await route.fetch(); // the REAL backend applies it...
    await route.abort('failed'); // ...but the browser never gets the answer
  });
  await modal().getByRole('button', { name: 'Confirmar activación' }).click();
  await expect(modal()).toContainText('No podemos confirmar el resultado de la activación');
  await expect(page.getByText(`La temporada ${YEARS.lost} es la activa.`)).toHaveCount(0);
  await page.waitForTimeout(500);
  expect(attempts).toBe(1);
  expect((await activeNow())?.id).toBe(target.id); // what really happened, read apart from the screen
  await page.unroute(`**/api/admin/temporadas/${target.id}/activar`);
  await modal().getByRole('button', { name: 'Consultar temporadas vigentes' }).click();
  await expect(modal()).toContainText('no confirma el resultado de la petición anterior');
  expect(attempts).toBe(1);
});

test('a visitor without a session cannot open the section', async ({ browser }) => {
  const anonymous = await browser.newContext({ baseURL: 'http://127.0.0.1:5173' });
  const visitor = await anonymous.newPage();
  const requests: string[] = [];
  visitor.on('request', (r) => { if (new URL(r.url()).pathname.startsWith('/api/admin/temporadas')) requests.push(r.url()); });
  await visitor.goto('/admin/temporadas');
  await expect(visitor).toHaveURL(/\/admin\/login/);
  expect(requests).toHaveLength(0);
  await anonymous.close();
});

test('the screen fits tablet and desktop widths without horizontal scroll, with confirmation controls reachable', async () => {
  await seed.season(YEARS.layout);
  for (const size of [{ width: 768, height: 900 }, { width: 1280, height: 800 }]) {
    await page.setViewportSize(size);
    await page.goto('/admin/temporadas');
    await expect(row(YEARS.layout)).toBeVisible();
    const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
    expect(overflow).toBeLessThanOrEqual(0);
    await expect(activate(YEARS.layout)).toBeEnabled();
    const box = await activate(YEARS.layout).boundingBox();
    expect(box!.height).toBeGreaterThanOrEqual(44);
  }
});
