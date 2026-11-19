import { randomBytes } from 'node:crypto';
import { expect, test, type BrowserContext, type Page, type Request } from '@playwright/test';
import { AdminSeed, loginAdminUi } from './support/seed';

test.describe.configure({ mode: 'serial' });
let context: BrowserContext; let page: Page; let seed: AdminSeed;
let family: string; let other: string; let inactive: string;
let athletes: { id: string; dni: string; name: string }[];
const suffix = `U11D${randomBytes(4).toString('hex')}`;
const writes: { url: string; body: unknown }[] = [];
const path = () => `/admin/familias/${family}`;
const modal = () => page.getByRole('dialog');
const checkbox = (index: number) => page.getByRole('checkbox', { name: new RegExp(athletes[index]!.name) });
async function state(index: number) {
  const response = await context.request.get(`/api/admin/deportistas/${athletes[index]!.id}/vinculos`);
  expect(response.status()).toBe(200);
  return await response.json() as { versionVinculos: string; vinculos: { vinculoId: string; familiaId: string; estado: string; esPrincipal: boolean }[] };
}
async function open() {
  await page.goto(path()); await page.getByRole('button', { name: 'Vincular deportistas' }).click();
  await page.getByRole('textbox', { name: 'Buscar deportistas' }).fill(suffix);
  await expect(page.getByText(/Página 1 de 2/)).toBeVisible(); await expect(checkbox(0)).toBeEnabled();
}
async function prepare(indices: number[]) {
  for (const i of indices) await checkbox(i).check();
  await page.getByRole('button', { name: 'Revisar selección', exact: true }).click(); await expect(modal()).toBeVisible();
}
async function confirm() { await modal().getByRole('button', { name: 'Confirmar vinculación', exact: true }).click(); }

test.beforeAll(async ({ browser }) => {
  context = await browser.newContext({ baseURL: 'http://127.0.0.1:5173' }); page = await context.newPage(); await loginAdminUi(page); seed = new AdminSeed(context.request);
  family = (await seed.createFamily(`E2E Vinculación ${suffix}`)).id; other = (await seed.createFamily(`E2E Previa ${suffix}`)).id;
  const disabled = await seed.createFamily(`E2E Inactiva ${suffix}`); inactive = disabled.id; await seed.deactivateFamily(inactive, disabled.version);
  athletes = [];
  for (let i = 0; i < 23; i++) {
    const name = `D${String(i).padStart(2, '0')} ${suffix}`;
    athletes.push({ ...await seed.createAthlete({ nombre: name, apellido: 'Sintetico' }), name });
  }
  await seed.link(other, [athletes[0]!.id]); await seed.link(family, [athletes[0]!.id, athletes[1]!.id]); await seed.revoke(family, athletes[0]!.id);
  page.on('request', (r) => { if (r.method() === 'POST' && /^\/api\/admin\/familias\/[^/]+\/deportistas$/.test(new URL(r.url()).pathname)) writes.push({ url: r.url(), body: r.postDataJSON() as unknown }); });
});
test.afterAll(async () => { await context.close(); });

test('confirmation explains access, traps/restores focus, cancels with Escape and reads no full athlete profiles', async () => {
  const reads: string[] = []; const listener = (r: Request) => { if (r.method() === 'GET') reads.push(new URL(r.url()).pathname); }; page.on('request', listener);
  await open(); const before = writes.length; await prepare([0, 2]);
  await expect(modal()).toHaveAccessibleName('Confirmar vinculación de deportistas'); await expect(modal()).toHaveAttribute('aria-describedby');
  await expect(modal()).toContainText(/obtendrá acceso/); await expect(modal()).toContainText(/no transfiere/); await expect(modal()).toContainText(/principal la decide el backend/);
  const cancel = modal().getByRole('button', { name: 'Cancelar' }); await expect(cancel).toBeFocused();
  await page.keyboard.press('Shift+Tab'); await expect(modal().getByRole('button', { name: 'Confirmar vinculación' })).toBeFocused();
  await page.keyboard.press('Tab'); await expect(cancel).toBeFocused(); await page.keyboard.press('Escape'); await expect(modal()).toHaveCount(0);
  await expect(page.getByRole('button', { name: 'Revisar selección', exact: true })).toBeFocused(); await expect(checkbox(0)).toBeChecked(); expect(writes.length).toBe(before);
  expect(reads.some((p) => /^\/api\/admin\/deportistas\/[^/]+$/.test(p))).toBe(false); expect(await page.locator('body').innerText()).not.toContain(athletes[0]!.dni); page.off('request', listener);
});

test('reactivates history, keeps an existing link and creates another in one atomic batch without transferring principal', async () => {
  const old = (await state(0)).vinculos.find((v) => v.familiaId === family)!;
  await open(); await expect(page.getByText('Revocado: se reactivará')).toBeVisible(); await expect(page.getByText('Activo: sin cambios')).toBeVisible();
  await prepare([0, 1, 2]); await confirm(); await expect(page.getByRole('status').filter({ hasText: 'Vinculación confirmada: 1 creados, 1 reactivados, 1 sin cambios.' })).toBeVisible();
  const now = await state(0); expect(now.vinculos.find((v) => v.familiaId === family)).toMatchObject({ vinculoId: old.vinculoId, estado: 'ACTIVO', esPrincipal: false }); expect(now.vinculos.find((v) => v.familiaId === other)).toMatchObject({ estado: 'ACTIVO', esPrincipal: true });
  const request = writes.at(-1)!.body as { deportistas: { deportistaId: string; versionEsperada: string }[] }; expect(request.deportistas.map((r) => r.deportistaId)).toEqual([0, 1, 2].map((i) => athletes[i]!.id)); expect(request.deportistas[0]!.versionEsperada).toBe(String(BigInt(now.versionVinculos) - 1n));
  await expect(page.getByRole('region', { name: 'Deportistas vinculados' }).getByRole('list', { name: 'Vínculos activos' }).getByRole('listitem')).toHaveCount(3);
});

test('preserves selection across the real paginated admin list without putting search or personal identifiers in navigation', async () => {
  await open(); await checkbox(3).check(); await page.getByRole('button', { name: 'Siguiente' }).click(); await expect(page.getByText(/Página 2 de 2/)).toBeVisible(); await expect(checkbox(20)).toBeEnabled(); await checkbox(20).check();
  await expect(page.getByText('2 seleccionados de 50')).toBeVisible(); expect(page.url()).not.toContain(suffix);
  await page.getByRole('button', { name: 'Revisar selección', exact: true }).click(); await expect(modal()).toContainText(athletes[3]!.name); await expect(modal()).toContainText(athletes[20]!.name); await modal().getByRole('button', { name: 'Cancelar' }).click();
});

test('does not offer a selector for an inactive family', async () => {
  await page.goto(`/admin/familias/${inactive}`); await expect(page.getByRole('button', { name: 'Vincular deportistas' })).toBeDisabled(); await expect(page.getByRole('textbox', { name: 'Buscar deportistas' })).toHaveCount(0);
});

test('stale member rejects the whole real batch; preserves intent and requires another explicit confirmation', async () => {
  await open(); await prepare([4, 5]); const before = writes.length; const captured = (await state(5)).versionVinculos;
  await seed.link(other, [athletes[5]!.id]); await confirm(); await expect(modal().getByRole('alert')).toContainText('Los vínculos cambiaron');
  expect((writes.at(-1)!.body as { deportistas: { versionEsperada: string }[] }).deportistas[1]!.versionEsperada).toBe(captured);
  for (const i of [4, 5]) expect((await state(i)).vinculos.some((v) => v.familiaId === family)).toBe(false);
  await expect(modal().getByRole('button', { name: 'Confirmar vinculación', exact: true })).toHaveCount(0);
  await modal().getByRole('button', { name: 'Consultar datos vigentes' }).click(); await modal().getByRole('button', { name: 'Revisar nueva confirmación' }).click(); expect(writes.length).toBe(before + 1);
  await expect(modal().getByRole('button', { name: 'Cancelar' })).toBeFocused(); await confirm(); await expect(page.getByText('Vinculación confirmada: 2 creados, 0 reactivados, 0 sin cambios.')).toBeVisible();
});

test('a lost response after a real commit is uncertain; consulting cannot claim success or retry the operation', async () => {
  await open(); await prepare([6]); const before = writes.length;
  await page.route(`**/api/admin/familias/${family}/deportistas`, async (route) => {
    if (route.request().method() !== 'POST') return route.continue();
    const response = await route.fetch(); expect(response.status()).toBe(200); await route.abort('failed');
  });
  await confirm(); await expect(modal().getByRole('alert')).toContainText('No sabemos si la vinculación se aplicó'); expect(writes.length).toBe(before + 1); expect((await state(6)).vinculos.some((v) => v.familiaId === family && v.estado === 'ACTIVO')).toBe(true);
  await page.unroute(`**/api/admin/familias/${family}/deportistas`); await modal().getByRole('button', { name: 'Consultar datos vigentes' }).click(); await expect(modal().getByRole('status')).toContainText('Esto no confirma el resultado'); await expect(modal()).toContainText('Sin cambios'); expect(writes.length).toBe(before + 1); await expect(page.getByText(/Vinculación confirmada:/)).toHaveCount(0);
  await modal().getByRole('button', { name: 'Cancelar' }).click();
  await expect(page.getByRole('region', { name: 'Deportistas vinculados' }).getByRole('link', { name: `${athletes[6]!.name} Sintetico`, exact: true })).toBeVisible();
  expect(writes.length).toBe(before + 1); await expect(page.getByText(/Vinculación confirmada:/)).toHaveCount(0);
  await page.getByRole('button', { name: 'Consultar y revisar selección' }).click(); await confirm(); await expect(page.getByText('Vinculación confirmada: 0 creados, 0 reactivados, 1 sin cambios.')).toBeVisible(); expect(writes.length).toBe(before + 2);
});

test('suppresses double submission while pending and keeps its lock after SPA navigation away and back', async () => {
  await open(); await prepare([7]); let release!: () => void; const held = new Promise<void>((resolve) => { release = resolve; });
  await page.route(`**/api/admin/familias/${family}/deportistas`, async (route) => { if (route.request().method() === 'POST') await held; await route.continue(); });
  const before = writes.length; const button = modal().getByRole('button', { name: 'Confirmar vinculación', exact: true }); await button.evaluate((b: HTMLButtonElement) => { b.click(); b.click(); });
  await expect(button).toBeDisabled(); await expect.poll(() => writes.length).toBe(before + 1);
  // History traversal uses the app's existing router, not a reload (pending mutation belongs to this runtime).
  await page.getByRole('link', { name: 'Familias', exact: true }).first().evaluate((a: HTMLAnchorElement) => a.click()); await expect(page).toHaveURL(/\/admin\/familias$/); await page.goBack();
  await expect(page.getByRole('button', { name: 'Vincular deportistas' })).toBeDisabled(); release(); await expect(page.getByRole('button', { name: 'Vincular deportistas' })).toBeEnabled(); expect(writes.length).toBe(before + 1); await page.unroute(`**/api/admin/familias/${family}/deportistas`);
});
