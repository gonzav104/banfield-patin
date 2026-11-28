import { randomBytes } from 'node:crypto';
import { expect, test, type Browser } from '@playwright/test';
import { AdminSeed, csrfHeaders, loginAdminUi } from './support/seed';

/**
 * «Mis datos» with the flag OFF, as in production (`banfield.autoservicio.perfil-habilitado=false`): this file runs in shard 3, whose
 * disposable backend starts with it off (E2E_PERFIL_FAMILIAR=false). The server answers the state but refuses every write before reading
 * or writing anything, and the screen says so without alarm and offers no form or action.
 */
const suffix = randomBytes(3).toString('hex');
const BASE = 'http://127.0.0.1:5173';

test('the family can look but nothing is written while the feature is off', async ({ browser }: { browser: Browser }) => {
  const adminContext = await browser.newContext({ baseURL: BASE });
  await loginAdminUi(await adminContext.newPage());
  const seed = new AdminSeed(adminContext.request);
  const anonymous = await browser.newContext({ baseURL: BASE });
  const account = await seed.createFamilyWithUser(anonymous.request, `E2E Perfil apagado ${suffix}`, {
    nombre: 'Rosa',
    apellido: `Apagada${suffix}`,
    email: `rosa.off.${suffix}@example.test`,
    password: `Fam-${randomBytes(8).toString('hex')}`,
  });
  await seed.createTutor(account.familyId, { nombre: `Laura${suffix}`, apellido: 'Apagada' });
  await anonymous.close();

  const context = await browser.newContext({ baseURL: BASE });
  const page = await context.newPage();
  await page.goto('/login');
  await page.getByLabel(/^Correo electrónico/).fill(account.email);
  await page.getByLabel(/^Contraseña/).fill(account.password);
  await page.getByRole('button', { name: 'Ingresar' }).click();
  await expect(page).toHaveURL(/\/familia$/);

  await page.goto('/familia/mis-datos');
  await expect(page.getByText(/todavía no está disponible/)).toBeVisible();
  await expect(page.getByRole('button', { name: /Soy |Guardar/ })).toHaveCount(0);

  const state = (await (await context.request.get('/api/familia/mi-perfil')).json()) as { habilitado: boolean; perfil: unknown };
  expect(state.habilitado).toBe(false);
  expect(state.perfil).toBeNull();
  const write = await context.request.post('/api/familia/mi-perfil', {
    data: { nombre: 'Rosa', apellido: 'Apagada' },
    headers: await csrfHeaders(context.request),
  });
  expect(write.status()).toBe(503);
  expect(((await write.json()) as { codigo: string }).codigo).toBe('AUTOSERVICIO_PERFIL_DESHABILITADO');
  await context.close();
  await adminContext.close();
});
