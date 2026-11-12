import { randomBytes } from 'node:crypto';
import { mkdirSync } from 'node:fs';
import { expect, request as playwrightRequest, test, type APIRequestContext, type Page } from '@playwright/test';
import { adminCredentials, loadState, saveState } from './support/state';
import { freshCode } from './support/totp';

/**
 * REAL flows: browser -> Vite proxy -> Spring backend -> PostgreSQL. Tests run in order and share one
 * administrator whose TOTP secret is captured from the enrolment screen (kept only in e2e/.state.json).
 */
test.describe.configure({ mode: 'serial' });

const ARTIFACTS = 'e2e/artifacts';
mkdirSync(ARTIFACTS, { recursive: true });

async function csrfHeaders(api: APIRequestContext): Promise<Record<string, string>> {
  await api.get('/api/auth/csrf');
  const state = await api.storageState();
  const cookie = state.cookies.find((c) => c.name === 'XSRF-TOKEN');
  if (!cookie) throw new Error('No XSRF-TOKEN cookie after GET /api/auth/csrf');
  return { 'X-XSRF-TOKEN': decodeURIComponent(cookie.value) };
}

async function submitAdminPassword(page: Page): Promise<void> {
  const { email, password } = adminCredentials();
  await page.goto('/admin/login');
  await page.getByLabel(/^Correo electrónico/).fill(email);
  await page.getByLabel(/^Contraseña/).fill(password);
  await page.getByRole('button', { name: 'Continuar' }).click();
  await expect(page).toHaveURL(/\/admin\/mfa$/);
}

async function submitTotp(page: Page, buttonName: string): Promise<void> {
  const state = loadState();
  if (!state.adminSecret) throw new Error('Missing admin TOTP secret: run the enrolment test first.');
  const { code, step } = await freshCode(state.adminSecret, state.lastTotpStep ?? null);
  saveState({ ...state, lastTotpStep: step });
  await page.getByLabel(/^Código de 6 dígitos/).fill(code);
  await page.getByRole('button', { name: buttonName }).click();
}

async function logoutViaUi(page: Page, expectedUrl: RegExp): Promise<void> {
  await page.getByRole('button', { name: 'Cerrar sesión' }).click();
  await expect(page).toHaveURL(expectedUrl);
}

test.describe('administrator (real backend)', () => {
  test('first login enrols TOTP through the UI and seeds a family account through the real API', async ({ page }) => {
    test.skip(!!loadState().adminSecret, 'Administrator already enrolled in a previous run (secret in e2e/.state.json).');
    await submitAdminPassword(page);

    await expect(page.getByRole('heading', { name: 'Configurá la verificación en dos pasos' })).toBeVisible();
    await page.screenshot({ path: `${ARTIFACTS}/admin-mfa-intro-desktop.png` });
    await page.getByRole('button', { name: 'Empezar configuración' }).click();
    const secret = ((await page.getByTestId('mfa-secret').textContent()) ?? '').replace(/\s/g, '');
    expect(secret).toMatch(/^[A-Z2-7]{16,}$/);
    await expect(page.getByRole('img', { name: /Código QR/ })).toBeVisible();
    await page.screenshot({ path: `${ARTIFACTS}/admin-mfa-enroll-desktop.png` });
    saveState({ ...loadState(), adminSecret: secret });

    await submitTotp(page, 'Confirmar y activar');
    await expect(page).toHaveURL(/\/admin$/);
    await expect(page.getByRole('heading', { name: 'Hola, Admin' })).toBeVisible();
    await expect(page.getByText('Activa')).toBeVisible();

    // Seed a family user the only way the backend allows: admin invitation + registration by token.
    const api = page.context().request;
    const suffix = randomBytes(4).toString('hex');
    const familia = await api.post('/api/admin/familias', {
      data: { nombreReferencia: `E2E ${suffix}` },
      headers: await csrfHeaders(api),
    });
    expect(familia.status()).toBe(201);
    const familiaId = ((await familia.json()) as { id: string }).id;
    const email = `e2e.${suffix}@example.test`;
    const invitation = await api.post('/api/admin/invitaciones', {
      data: { familiaId, emailSugerido: email, diasVigencia: 1 },
      headers: await csrfHeaders(api),
    });
    expect(invitation.status()).toBe(201);
    const token = ((await invitation.json()) as { token: string }).token;

    const anonymous = await playwrightRequest.newContext({ baseURL: 'http://127.0.0.1:5173' });
    const password = `Fam-${randomBytes(8).toString('hex')}`;
    const registration = await anonymous.post('/api/auth/registro/invitacion', {
      data: { token, nombre: 'Lucía', apellido: 'Prueba', email, password },
      headers: await csrfHeaders(anonymous),
    });
    expect(registration.status()).toBe(201);
    await anonymous.dispose();
    saveState({ ...loadState(), family: { email, password, nombre: 'Lucía' } });
  });

  test('a valid password alone never opens the panel; the second factor does, and the session survives a reload', async ({ page }) => {
    await submitAdminPassword(page);
    await page.screenshot({ path: `${ARTIFACTS}/admin-mfa-verify-desktop.png` });

    // Pending session: neither the panel route nor the admin API accept it.
    await page.goto('/admin');
    await expect(page).toHaveURL(/\/admin\/mfa$/);
    await expect(page.getByText('Panel administrativo')).toHaveCount(0);
    expect((await page.request.get('/api/admin/familias')).status()).toBe(403);
    await page.goto('/familia');
    await expect(page).toHaveURL(/\/admin\/mfa$/);

    // A wrong code is rejected without ending the pending session.
    await page.getByLabel(/^Código de 6 dígitos/).fill('000000');
    await page.getByRole('button', { name: 'Verificar e ingresar' }).click();
    await expect(page.getByRole('alert')).toContainText('El código no es correcto');
    await expect(page).toHaveURL(/\/admin\/mfa$/);

    await submitTotp(page, 'Verificar e ingresar');
    await expect(page).toHaveURL(/\/admin$/);
    await expect(page.getByRole('heading', { name: 'Hola, Admin' })).toBeVisible();
    await page.screenshot({ path: `${ARTIFACTS}/admin-home-desktop.png` });

    // Session restore through /api/auth/me, and the cookie is not readable by scripts.
    await page.reload();
    await expect(page.getByRole('heading', { name: 'Hola, Admin' })).toBeVisible();
    const cookies = await page.context().cookies();
    const session = cookies.find((c) => c.name === 'BP_SESION');
    expect(session?.httpOnly).toBe(true);
    const visible = await page.evaluate(() => ({
      cookie: document.cookie,
      local: Object.keys(localStorage),
      session: Object.keys(sessionStorage),
    }));
    expect(visible.cookie).not.toContain('BP_SESION');
    expect(visible.local).toEqual([]);
    expect(visible.session).toEqual([]);

    // Role protection: an administrator does not get the family area.
    await page.goto('/familia');
    await expect(page.getByRole('heading', { name: 'No tenés permiso para ver esta sección' })).toBeVisible();

    await page.setViewportSize({ width: 390, height: 844 });
    await page.goto('/admin');
    await expect(page.getByRole('heading', { name: 'Hola, Admin' })).toBeVisible();
    await page.screenshot({ path: `${ARTIFACTS}/admin-home-mobile.png`, fullPage: true });
    await page.setViewportSize({ width: 1280, height: 720 });

    await logoutViaUi(page, /\/admin\/login$/);
    expect((await page.request.get('/api/auth/me')).status()).toBe(401);
    await page.goto('/admin');
    await expect(page).toHaveURL(/\/admin\/login$/);
  });

  test('wrong administrator password shows a generic error', async ({ page }) => {
    await page.goto('/admin/login');
    await page.getByLabel(/^Correo electrónico/).fill(adminCredentials().email);
    await page.getByLabel(/^Contraseña/).fill('definitely-wrong-password');
    await page.getByRole('button', { name: 'Continuar' }).click();
    await expect(page.getByRole('alert')).toContainText('El correo o la contraseña no son correctos');
    await expect(page).toHaveURL(/\/admin\/login$/);
  });
});

test.describe('family (real backend)', () => {
  test('rejects wrong credentials, logs in, restores the session, is kept out of /admin, and logs out for real', async ({ page }) => {
    const family = loadState().family;
    if (!family) throw new Error('Missing seeded family user: run the administrator enrolment test first.');

    await page.goto('/login');
    await expect(page.getByRole('heading', { name: 'Ingresá a tu portal' })).toBeVisible();
    await expect(page.getByRole('link')).toHaveCount(0);
    await page.screenshot({ path: `${ARTIFACTS}/login-family-desktop.png` });

    await page.getByLabel(/^Correo electrónico/).fill(family.email);
    await page.getByLabel(/^Contraseña/).fill('wrong-password-123');
    await page.getByRole('button', { name: 'Ingresar' }).click();
    await expect(page.getByRole('alert')).toContainText('no son correctos');

    await page.getByLabel(/^Contraseña/).fill(family.password);
    await page.getByRole('button', { name: 'Ingresar' }).click();
    await expect(page).toHaveURL(/\/familia$/);
    await expect(page.getByRole('heading', { name: `Hola, ${family.nombre}` })).toBeVisible();
    await expect(page.getByText(family.email).first()).toBeVisible();

    await page.setViewportSize({ width: 390, height: 844 });
    await page.screenshot({ path: `${ARTIFACTS}/familia-home-mobile.png`, fullPage: true });
    await page.setViewportSize({ width: 1280, height: 720 });

    await page.reload();
    await expect(page.getByRole('heading', { name: `Hola, ${family.nombre}` })).toBeVisible();

    // The family role is rejected by the backend itself, not just hidden in the UI.
    const adminApi = await page.request.get('/api/admin/familias');
    expect(adminApi.status()).toBe(403);
    await page.goto('/admin');
    await expect(page.getByRole('heading', { name: 'No tenés permiso para ver esta sección' })).toBeVisible();
    await page.goto('/login');
    await expect(page).toHaveURL(/\/familia$/);

    const stored = await page.evaluate(() => ({ local: Object.keys(localStorage), session: Object.keys(sessionStorage) }));
    expect(stored).toEqual({ local: [], session: [] });
    expect((await page.context().cookies()).find((c) => c.name === 'BP_SESION')?.httpOnly).toBe(true);

    await logoutViaUi(page, /\/login$/);
    expect((await page.request.get('/api/auth/me')).status()).toBe(401);
    await page.goto('/familia');
    await expect(page).toHaveURL(/\/login$/);
  });

  test('an administrator cannot use the family login', async ({ page }) => {
    await page.goto('/login');
    await page.getByLabel(/^Correo electrónico/).fill(adminCredentials().email);
    await page.getByLabel(/^Contraseña/).fill(adminCredentials().password);
    await page.getByRole('button', { name: 'Ingresar' }).click();
    await expect(page.getByRole('alert')).toContainText('no son correctos');
  });
});

test.describe('simulated outages (browser-level mock of /api/auth/me, not a real outage)', () => {
  test('503 shows the unavailable screen and recovers on retry; no connection shows its own message', async ({ page }) => {
    await page.route('**/api/auth/me', (route) =>
      route.fulfill({ status: 503, contentType: 'application/json', body: JSON.stringify({ codigo: 'SERVICIO_NO_DISPONIBLE', mensaje: 'x' }) }),
    );
    await page.goto('/familia');
    await expect(page.getByRole('heading', { name: 'Servicio no disponible' })).toBeVisible();
    await page.screenshot({ path: `${ARTIFACTS}/unavailable-desktop.png` });

    await page.unroute('**/api/auth/me');
    await page.route('**/api/auth/me', (route) => route.abort('connectionrefused'));
    await page.getByRole('button', { name: 'Reintentar' }).click();
    await expect(page.getByRole('heading', { name: 'Sin conexión con el servidor' })).toBeVisible();

    await page.unroute('**/api/auth/me');
    await page.getByRole('button', { name: 'Reintentar' }).click();
    await expect(page).toHaveURL(/\/login$/);
  });
});

test.describe('layout checks', () => {
  for (const viewport of [
    { name: 'mobile', width: 390, height: 844 },
    { name: 'desktop', width: 1280, height: 720 },
  ]) {
    test(`public login screens fit without horizontal scroll (${viewport.name})`, async ({ page }) => {
      await page.setViewportSize({ width: viewport.width, height: viewport.height });
      for (const path of ['/login', '/admin/login']) {
        await page.goto(path);
        await page.getByRole('button', { name: /Ingresar|Continuar/ }).waitFor();
        const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
        expect(overflow, `${path} overflows horizontally`).toBeLessThanOrEqual(0);
        await page.screenshot({ path: `${ARTIFACTS}/${path.slice(1).replaceAll('/', '-')}-${viewport.name}.png` });
      }
    });
  }
});
