import { randomBytes } from 'node:crypto';
import { expect, test, type Browser, type BrowserContext, type Page } from '@playwright/test';
import { AdminSeed, csrfHeaders, loginAdminUi, type SeededFamily } from './support/seed';

/**
 * REAL flow of U21-BIS C0 (browser -> Vite proxy -> Spring backend -> PostgreSQL): two adults of the SAME family, each with
 * their own credentials; ADMIN removes the access of one from the family detail. The session that person already had open dies
 * on the next request, the other adult keeps working, and giving the access back does not revive the old session (a new sign-in
 * is needed). All data is synthetic. Needs CUENTAS_GESTION_HABILITADA, which the harness turns on only in its disposable base.
 */
test.describe.configure({ mode: 'serial' });

const suffix = randomBytes(3).toString('hex');
const password = () => `Fam-${randomBytes(8).toString('hex')}`;
const BASE = 'http://127.0.0.1:5173';

let browserRef: Browser;
let adminContext: BrowserContext;
let adminPage: Page;
let madre: SeededFamily;
let padre: SeededFamily;

async function familyPage(account: SeededFamily): Promise<{ context: BrowserContext; page: Page }> {
  const context = await browserRef.newContext({ baseURL: BASE });
  const page = await context.newPage();
  await page.goto('/login');
  await page.getByLabel(/^Correo electrónico/).fill(account.email);
  await page.getByLabel(/^Contraseña/).fill(account.password);
  await page.getByRole('button', { name: 'Ingresar' }).click();
  await expect(page).toHaveURL(/\/familia$/);
  return { context, page };
}

const sessionStatus = async (context: BrowserContext) => (await context.request.get('/api/auth/me')).status();

test.beforeAll(async ({ browser }) => {
  browserRef = browser;
  adminContext = await browser.newContext({ baseURL: BASE });
  adminPage = await adminContext.newPage();
  await loginAdminUi(adminPage);
  const seed = new AdminSeed(adminContext.request);
  const anonymous = await browser.newContext({ baseURL: BASE });
  madre = await seed.createFamilyWithUser(anonymous.request, `E2E Cuentas ${suffix}`, { nombre: 'Laura', apellido: 'Cuentas', email: `madre.${suffix}@example.test`, password: password() });
  // Second adult of the SAME family: an invitation to the existing family, with independent credentials.
  const invitation = await adminContext.request.post('/api/admin/invitaciones', {
    data: { familiaId: madre.familyId, emailSugerido: `padre.${suffix}@example.test` },
    headers: await csrfHeaders(adminContext.request),
  });
  expect(invitation.ok()).toBe(true);
  const { token } = (await invitation.json()) as { token: string };
  const pass = password();
  const registered = await anonymous.request.post('/api/auth/registro/invitacion', {
    data: { token, nombre: 'Martín', apellido: 'Cuentas', email: `padre.${suffix}@example.test`, password: pass },
    headers: await csrfHeaders(anonymous.request),
  });
  expect(registered.ok()).toBe(true);
  padre = { familyId: madre.familyId, email: `padre.${suffix}@example.test`, password: pass, nombre: 'Martín', name: madre.name };
  await anonymous.close();
});

test.afterAll(async () => {
  await adminContext.close();
});

test.describe('family access accounts (real backend)', () => {
  test('removing access ends the open session of that adult only, and giving it back needs a new sign-in', async () => {
    const madreSession = await familyPage(madre);
    const padreSession = await familyPage(padre);
    // The cookie of her open session, kept as it was: the 401 that follows the removal erases it from that browser.
    const openSession = await madreSession.context.storageState();

    await adminPage.goto(`/admin/familias/${madre.familyId}`);
    const accounts = adminPage.getByRole('list', { name: 'Cuentas de acceso' });
    await expect(accounts.getByRole('listitem')).toHaveCount(2);
    await expect(accounts.getByText('Puede ingresar')).toHaveCount(2);

    await accounts.getByRole('button', { name: 'Quitar acceso de Laura Cuentas' }).click();
    const dialog = adminPage.getByRole('dialog', { name: 'Quitar el acceso a Laura Cuentas' });
    await expect(dialog.getByText(/No se borra ningún dato de la familia/)).toBeVisible();
    await dialog.getByRole('button', { name: 'Quitar acceso' }).click();
    await expect(adminPage.getByText('Listo: Laura Cuentas ya no puede ingresar.')).toBeVisible();
    await expect(accounts.getByText('Sin acceso')).toHaveCount(1);

    // The session she already had open is cut on its next request; his keeps working.
    expect(await sessionStatus(madreSession.context)).toBe(401);
    await madreSession.page.reload();
    await expect(madreSession.page).toHaveURL(/\/login/);
    expect(await sessionStatus(padreSession.context)).toBe(200);
    await padreSession.page.reload();
    await expect(padreSession.page).toHaveURL(/\/familia$/);

    // She cannot sign in again while the access is removed.
    const blocked = await browserRef.newContext({ baseURL: BASE });
    const login = await blocked.request.post('/api/auth/login', { data: { email: madre.email, password: madre.password }, headers: await csrfHeaders(blocked.request) });
    expect(login.status()).toBe(401);
    await blocked.close();

    // Giving the access back does NOT revive the session opened before; a new sign-in works.
    const oldContext = await browserRef.newContext({ baseURL: BASE, storageState: openSession });
    await madreSession.context.close();
    await accounts.getByRole('button', { name: 'Devolver acceso de Laura Cuentas' }).click();
    await adminPage.getByRole('dialog', { name: 'Devolver el acceso a Laura Cuentas' }).getByRole('button', { name: 'Devolver acceso' }).click();
    await expect(adminPage.getByText('Listo: Laura Cuentas puede volver a ingresar.')).toBeVisible();
    await expect(accounts.getByText('Puede ingresar')).toHaveCount(2);
    expect(await sessionStatus(oldContext)).toBe(401);
    await oldContext.close();
    const fresh = await familyPage(madre);
    expect(await sessionStatus(fresh.context)).toBe(200);
    await fresh.context.close();
    await padreSession.context.close();
  });
});
