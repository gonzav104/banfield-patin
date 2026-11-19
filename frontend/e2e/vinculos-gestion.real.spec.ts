import { randomBytes } from 'node:crypto';
import { expect, test, type BrowserContext, type Page } from '@playwright/test';
import { AdminSeed, loginAdminUi } from './support/seed';

// REAL browser -> backend -> disposable PostgreSQL. Only the explicitly labelled lost-response scenario intercepts transport.
test.describe.configure({ mode: 'serial' });
let context: BrowserContext;
let page: Page;
let seed: AdminSeed;
let one: string;
let two: string;
let inactive: string;
let athlete: string;
let staleAthlete: string;
let uncertainAthlete: string;
let keyboardAthlete: string;
const suffix = randomBytes(4).toString('hex');
const names = { one: `E2E Uno ${suffix}`, two: `E2E Dos ${suffix}`, inactive: `E2E Inactiva ${suffix}` };
const path = (id: string) => `/admin/deportistas/${id}/vinculos`;
const row = (name: string) => page.getByRole('listitem').filter({ has: page.getByRole('link', { name, exact: true }) });
const modal = () => page.getByRole('dialog');
const writes: string[] = [];
async function state(id: string) {
  const response = await context.request.get(`/api/admin/deportistas/${id}/vinculos`);
  expect(response.status()).toBe(200);
  return await response.json() as { versionVinculos: string; vinculos: { familiaId: string; estado: string; esPrincipal: boolean }[] };
}

test.beforeAll(async ({ browser }) => {
  context = await browser.newContext({ baseURL: 'http://127.0.0.1:5173' });
  page = await context.newPage();
  await loginAdminUi(page);
  seed = new AdminSeed(context.request);
  one = (await seed.createFamily(names.one)).id;
  two = (await seed.createFamily(names.two)).id;
  inactive = (await seed.createFamily(names.inactive)).id;
  const ids: string[] = [];
  for (const nombre of ['Acciones', 'Desactualizado', 'Incierto', 'Teclado']) {
    const created = await seed.createAthlete({ nombre: `${nombre} ${suffix}`, apellido: 'Sintetico' });
    ids.push(created.id);
    await seed.link(one, [created.id]);
    await seed.link(two, [created.id]);
  }
  [athlete, staleAthlete, uncertainAthlete, keyboardAthlete] = ids as [string, string, string, string];
  await seed.link(inactive, [athlete]);
  const family = await (await context.request.get(`/api/admin/familias/${inactive}`)).json() as { version: string };
  await seed.deactivateFamily(inactive, family.version);
  page.on('request', (request) => {
    if (request.method() === 'POST' && /\/(revocar|principal)$/.test(new URL(request.url()).pathname)) writes.push(request.url());
  });
});
test.afterAll(async () => { await context.close(); });

test('both confirmations explain consequences; cancellation and Escape do not write; modal traps and restores focus', async () => {
  await page.goto(path(athlete));
  for (const action of ['Revocar vínculo', 'Cambiar principal']) {
    const trigger = row(names.two).getByRole('button', { name: action });
    await expect(trigger).toBeEnabled();
    const before = writes.length;
    await trigger.click();
    await expect(modal()).toHaveAccessibleName(action);
    await expect(modal()).toHaveAttribute('aria-describedby');
    const cancel = modal().getByRole('button', { name: 'Cancelar' });
    await expect(cancel).toBeFocused();
    await page.keyboard.press('Tab');
    await expect(modal().getByRole('button', { name: /^Confirmar/ })).toBeFocused();
    await page.keyboard.press('Shift+Tab');
    await expect(cancel).toBeFocused();
    await page.keyboard.press('Escape');
    await expect(modal()).toHaveCount(0);
    await expect(trigger).toBeFocused();
    expect(writes.length).toBe(before);
  }
});

test('disabled family cannot become principal; existing principal offers no redundant principal action', async () => {
  await page.goto(path(athlete));
  await expect(row(names.inactive).getByRole('button', { name: 'Cambiar principal' })).toBeDisabled();
  await expect(row(names.one).getByRole('button', { name: 'Cambiar principal' })).toHaveCount(0);
  await expect(row(names.inactive).getByRole('button', { name: 'Revocar vínculo' })).toBeEnabled();
});

test('changes principal without revoking other families and sends the observed revision', async () => {
  await page.goto(path(athlete));
  const before = await state(athlete);
  await row(names.two).getByRole('button', { name: 'Cambiar principal' }).click();
  await expect(modal()).toContainText('no revoca las demás familias activas ni transfiere automáticamente');
  const request = page.waitForRequest((r) => r.method() === 'POST' && r.url().endsWith('/principal'));
  await modal().getByRole('button', { name: 'Confirmar cambio de principal' }).click();
  expect((await request).postDataJSON()).toEqual({ versionEsperada: before.versionVinculos });
  await expect(page.getByText('Familia principal actualizada.', { exact: true })).toBeVisible();
  const after = await state(athlete);
  expect(after.vinculos.every((link) => link.estado === 'ACTIVO')).toBe(true);
  expect(after.vinculos.filter((link) => link.esPrincipal).map((link) => link.familiaId)).toEqual([two]);
});

test('revokes the principal, preserves history and never promotes another family', async () => {
  await page.goto(path(athlete));
  await row(names.two).getByRole('button', { name: 'Revocar vínculo' }).click();
  await expect(modal()).toContainText('conserva el historial y no promueve automáticamente');
  await modal().getByRole('button', { name: 'Confirmar revocación' }).click();
  await expect(page.getByText('Vínculo revocado.', { exact: true })).toBeVisible();
  const after = await state(athlete);
  expect(after.vinculos.find((link) => link.familiaId === two)?.estado).toBe('REVOCADO');
  expect(after.vinculos.some((link) => link.esPrincipal)).toBe(false);
  expect(after.vinculos).toHaveLength(3);
  await expect(row(names.two).getByRole('button')).toHaveCount(0);
  await expect(page.getByText('Ninguna de las familias activas está marcada como principal.')).toBeVisible();
});

test('stale intent reads current links and requires a new explicit confirmation', async () => {
  await page.goto(path(staleAthlete));
  await row(names.two).getByRole('button', { name: 'Revocar vínculo' }).click();
  await seed.revoke(one, staleAthlete); // Real concurrent write increments the aggregate revision.
  const before = writes.length;
  await modal().getByRole('button', { name: 'Confirmar revocación' }).click();
  await expect(modal()).toContainText('Los vínculos cambiaron.');
  expect(writes.length).toBe(before + 1);
  await expect(modal().getByRole('button', { name: 'Confirmar revocación' })).toHaveCount(0);
  await modal().getByRole('button', { name: 'Consultar vínculos vigentes' }).click();
  await modal().getByRole('button', { name: 'Revisar nueva confirmación' }).click();
  expect(writes.length).toBe(before + 1);
  await modal().getByRole('button', { name: 'Confirmar revocación' }).click();
  await expect(page.getByText('Vínculo revocado.', { exact: true })).toBeVisible();
  expect(writes.length).toBe(before + 2);
});

test('simulated lost response AFTER real commit stays uncertain and verifies persisted state without retrying', async () => {
  await page.goto(path(uncertainAthlete));
  const url = `**/api/admin/familias/${two}/deportistas/${uncertainAthlete}/revocar`;
  await page.route(url, async (route) => {
    const response = await route.fetch();
    expect(response.status()).toBe(200); // The backend really persisted this write.
    await route.abort('failed'); // Only the delivery is lost.
  }, { times: 1 });
  const before = writes.length;
  await row(names.two).getByRole('button', { name: 'Revocar vínculo' }).click();
  await modal().getByRole('button', { name: 'Confirmar revocación' }).click();
  await expect(modal()).toContainText('No podemos confirmar el resultado');
  await modal().getByRole('button', { name: 'Consultar vínculos vigentes' }).click();
  await expect(modal()).toContainText('La consulta no confirma el resultado de la petición anterior.');
  expect(writes.length).toBe(before + 1);
  expect((await state(uncertainAthlete)).vinculos.find((link) => link.familiaId === two)?.estado).toBe('REVOCADO');
  await expect(page.getByText('Vínculo revocado.', { exact: true })).toHaveCount(0);
  await expect(modal().getByRole('button', { name: 'Revisar nueva confirmación' })).toHaveCount(0);
  await modal().getByRole('button', { name: 'Cancelar' }).click();
});

test('keyboard confirmation and rapid clicks produce exactly one real POST', async () => {
  await page.goto(path(keyboardAthlete));
  await row(names.two).getByRole('button', { name: 'Revocar vínculo' }).click();
  await page.keyboard.press('Shift+Tab');
  const confirm = modal().getByRole('button', { name: 'Confirmar revocación' });
  await expect(confirm).toBeFocused();
  const before = writes.length;
  await confirm.evaluate((button: HTMLButtonElement) => { button.click(); button.click(); });
  await expect(page.getByText('Vínculo revocado.', { exact: true })).toBeVisible();
  expect(writes.length).toBe(before + 1);
  expect((await state(keyboardAthlete)).vinculos.find((link) => link.familiaId === two)?.estado).toBe('REVOCADO');
});
