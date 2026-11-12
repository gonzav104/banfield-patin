import { execFileSync } from 'node:child_process';
import { randomBytes } from 'node:crypto';
import { mkdirSync } from 'node:fs';
import { expect, test, type Browser, type BrowserContext, type Page } from '@playwright/test';
import { adminCredentials, loadState, saveState } from './support/state';
import { freshCode } from './support/totp';

/**
 * REAL flows: browser -> Vite proxy -> Spring backend -> PostgreSQL. Requires the administrator enrolled by
 * auth.real.spec.ts (secret in e2e/.state.json). The backend must run with FRONTEND_URL_BASE=http://127.0.0.1:5173
 * so that `enlaceRegistro` points at this app.
 *
 * The backend rate-limits FAILED invitation validations per IP (5 per 15 minutes, then every call fails with the
 * same uniform error). This file causes 3, so restart the backend (or wait 15 minutes) between runs.
 */
test.describe.configure({ mode: 'serial' });

const ARTIFACTS = 'e2e/artifacts';
mkdirSync(ARTIFACTS, { recursive: true });

const LINK_PATTERN = /^http:\/\/127\.0\.0\.1:5173\/registro\/invitacion\/([A-Za-z0-9_-]{43})$/;
const suffix = randomBytes(3).toString('hex');
const familyName = `E2E Invitada ${suffix}`;
const newUser = { nombre: 'Ana', apellido: 'Invitada', email: `ana.${suffix}@example.test`, password: `Reg-${randomBytes(8).toString('hex')}` };

let browserRef: Browser;
let adminContext: BrowserContext;
let admin: Page;
let usedLink = '';

function dbQuery(sql: string): void {
  const container = process.env.E2E_DB_CONTAINER;
  if (!container) throw new Error('E2E_DB_CONTAINER is required for the expired-invitation test.');
  // psql reads the credentials from the container's own environment: nothing sensitive leaves it.
  execFileSync('docker', ['exec', container, 'sh', '-c', `psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "${sql}"`], { stdio: 'pipe' });
}

async function loginAdmin(page: Page): Promise<void> {
  const { email, password } = adminCredentials();
  const state = loadState();
  if (!state.adminSecret) throw new Error('Run auth.real.spec.ts first: the administrator is not enrolled.');
  await page.goto('/admin/login');
  await page.getByLabel(/^Correo electrónico/).fill(email);
  await page.getByLabel(/^Contraseña/).fill(password);
  await page.getByRole('button', { name: 'Continuar' }).click();
  await expect(page).toHaveURL(/\/admin\/mfa$/);
  const { code, step } = await freshCode(state.adminSecret, state.lastTotpStep ?? null);
  saveState({ ...loadState(), lastTotpStep: step });
  await page.getByLabel(/^Código de 6 dígitos/).fill(code);
  await page.getByRole('button', { name: 'Verificar e ingresar' }).click();
  await expect(page).toHaveURL(/\/admin$/);
}

async function createInvitationInUi(page: Page, options: { newFamily?: string; existingFamily?: string; email?: string }): Promise<string> {
  await page.getByRole('button', { name: 'Nueva invitación' }).click();
  if (options.existingFamily) {
    await page.getByLabel('Familia existente').check();
    await page.getByLabel('Buscar familia por nombre').fill(options.existingFamily);
    await page.getByLabel(options.existingFamily, { exact: true }).check();
  } else {
    await page.getByLabel(/^Nombre de referencia de la familia/).fill(options.newFamily ?? '');
  }
  if (options.email) await page.getByLabel(/^Correo sugerido/).fill(options.email);
  await page.getByRole('button', { name: 'Crear invitación' }).click();
  const link = await page.getByLabel('Enlace de registro').inputValue();
  expect(link).toMatch(LINK_PATTERN);
  return link;
}

async function anonymousPage(): Promise<{ context: BrowserContext; page: Page }> {
  const context = await browserRef.newContext({ baseURL: 'http://127.0.0.1:5173' });
  return { context, page: await context.newPage() };
}

test.beforeAll(async ({ browser }) => {
  browserRef = browser;
  adminContext = await browser.newContext({ baseURL: 'http://127.0.0.1:5173' });
  admin = await adminContext.newPage();
  await loginAdmin(admin);
});

test.afterAll(async () => {
  await adminContext.close();
});

test.describe('invitation lifecycle (real backend)', () => {
  test('admin creates an invitation for a NEW family and sees the link once', async () => {
    await admin.getByRole('link', { name: 'Invitaciones' }).click();
    await expect(admin.getByRole('heading', { name: 'Invitaciones' })).toBeVisible();
    await admin.screenshot({ path: `${ARTIFACTS}/invitations-list-desktop.png` });

    usedLink = await createInvitationInUi(admin, { newFamily: familyName, email: newUser.email });
    await expect(admin.getByText(/se muestra una sola vez/)).toBeVisible();
    await admin.screenshot({ path: `${ARTIFACTS}/invitations-created-desktop.png` });

    await admin.getByRole('button', { name: 'Listo, ya lo copié' }).click();
    await expect(admin.getByLabel('Enlace de registro')).toHaveCount(0);
    const row = admin.getByRole('listitem').filter({ hasText: familyName });
    await expect(row.getByText('Pendiente', { exact: true })).toBeVisible();
    expect(await admin.content()).not.toContain(usedLink.split('/').pop());

    const stored = await admin.evaluate(() => ({ local: Object.keys(localStorage), session: Object.keys(sessionStorage) }));
    expect(stored).toEqual({ local: [], session: [] });
  });

  test('the family registers through the link, is NOT signed in, then signs in with the new account', async () => {
    const { context, page } = await anonymousPage();
    await page.setViewportSize({ width: 390, height: 844 });
    await page.goto(usedLink);

    await expect(page.getByRole('heading', { name: 'Creá tu cuenta' })).toBeVisible();
    await expect(page.getByText(/Te invitaron a/)).toBeVisible();
    await expect(page).toHaveURL(/\/registro\/invitacion$/); // the token left the address bar
    await expect(page.getByLabel(/^Correo electrónico/)).toHaveValue(newUser.email);
    await page.screenshot({ path: `${ARTIFACTS}/registration-form-mobile.png`, fullPage: true });

    await page.getByLabel(/^Nombre/).fill(newUser.nombre);
    await page.getByLabel(/^Apellido/).fill(newUser.apellido);
    await page.getByLabel(/^Contraseña/).fill(newUser.password);
    await page.getByLabel(/^Repetir contraseña/).fill(newUser.password);
    await page.getByRole('button', { name: 'Crear mi cuenta' }).click();

    await expect(page).toHaveURL(/\/login$/);
    await expect(page.getByText('Tu cuenta fue creada.')).toBeVisible();
    expect((await page.request.get('/api/auth/me')).status()).toBe(401); // no automatic session
    await page.screenshot({ path: `${ARTIFACTS}/registration-success-login-mobile.png` });

    await page.getByLabel(/^Correo electrónico/).fill(newUser.email);
    await page.getByLabel(/^Contraseña/).fill(newUser.password);
    await page.getByRole('button', { name: 'Ingresar' }).click();
    await expect(page.getByRole('heading', { name: `Hola, ${newUser.nombre}` })).toBeVisible();

    // A family account cannot use the admin API or screen.
    expect((await page.request.get('/api/admin/invitaciones')).status()).toBe(403);
    await page.goto('/admin/invitaciones');
    await expect(page.getByRole('heading', { name: 'No tenés permiso para ver esta sección' })).toBeVisible();
    await context.close();
  });

  test('the used link no longer works (uniform message) and the admin list shows it as used', async () => {
    const { context, page } = await anonymousPage();
    await page.goto(usedLink);
    await expect(page.getByRole('heading', { name: 'Esta invitación no está disponible' })).toBeVisible();
    await expect(page.getByLabel(/^Contraseña/)).toHaveCount(0);
    await page.screenshot({ path: `${ARTIFACTS}/invitation-unavailable-mobile.png` });
    await context.close();

    await admin.reload();
    const row = admin.getByRole('listitem').filter({ hasText: familyName });
    await expect(row.getByText('Usada', { exact: true })).toBeVisible();
    await expect(row.getByRole('button', { name: /Revocar/ })).toHaveCount(0);
  });

  test('admin invites the EXISTING family, then revokes the invitation; the revoked link is unavailable', async () => {
    const link = await createInvitationInUi(admin, { existingFamily: familyName });
    await admin.getByRole('button', { name: 'Listo, ya lo copié' }).click();

    const row = admin.getByRole('listitem').filter({ hasText: familyName }).filter({ hasText: 'Pendiente' });
    await row.getByRole('button', { name: /Revocar invitación/ }).click();
    await admin.getByRole('button', { name: 'Sí, revocar' }).click();
    await expect(admin.getByRole('listitem').filter({ hasText: familyName }).filter({ hasText: 'Revocada' })).toHaveCount(1);
    await admin.screenshot({ path: `${ARTIFACTS}/invitations-revoked-desktop.png` });

    const { context, page } = await anonymousPage();
    await page.goto(link);
    await expect(page.getByRole('heading', { name: 'Esta invitación no está disponible' })).toBeVisible();
    await context.close();
  });

  test('an expired invitation is shown as expired to the admin and its link is unavailable', async () => {
    test.skip(!process.env.E2E_DB_CONTAINER, 'Set E2E_DB_CONTAINER (disposable dev DB container) to force expiry through SQL.');
    const expiredFamily = `E2E Vencida ${suffix}`;
    const link = await createInvitationInUi(admin, { newFamily: expiredFamily });
    await admin.getByRole('button', { name: 'Listo, ya lo copié' }).click();
    dbQuery(
      `update gestion_patin.invitacion set creado_en = now() - interval '3 days', expira_en = now() - interval '1 day' where familia_id in (select id from gestion_patin.familia where nombre_referencia = '${expiredFamily}')`,
    );

    await admin.getByLabel('Estado').selectOption('EXPIRADA');
    const row = admin.getByRole('listitem').filter({ hasText: expiredFamily });
    await expect(row.getByText('Vencida', { exact: true })).toBeVisible();
    await expect(row.getByRole('button', { name: /Revocar/ })).toHaveCount(0);

    const { context, page } = await anonymousPage();
    await page.goto(link);
    await expect(page.getByRole('heading', { name: 'Esta invitación no está disponible' })).toBeVisible();
    await context.close();
  });

  test('registration without a valid invitation never shows a form', async () => {
    const { context, page } = await anonymousPage();
    await page.goto('/registro');
    await expect(page.getByRole('heading', { name: 'Necesitás un enlace de invitación' })).toBeVisible();
    await expect(page.getByLabel(/^Contraseña/)).toHaveCount(0);
    await context.close();
  });

  test('anonymous users get 401 from the admin invitation API', async ({ request }) => {
    expect((await request.get('/api/admin/invitaciones')).status()).toBe(401);
  });
});
