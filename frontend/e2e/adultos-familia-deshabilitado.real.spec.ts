import { randomBytes, randomUUID } from 'node:crypto';
import { expect, test, type Browser } from '@playwright/test';
import { AdminSeed, csrfHeaders, loginAdminUi } from './support/seed';

/**
 * «Otros adultos» with the flag OFF, as in production (`banfield.autoservicio.tutores-habilitado=false`): this file runs in shard 3, whose
 * disposable backend starts with it off (E2E_ADULTOS_FAMILIA=false). The family can read the adults the club loaded, the server refuses every
 * write before reading or writing anything, and the screen says so without alarm and offers no add or edit.
 */
const suffix = randomBytes(3).toString('hex');
const BASE = 'http://127.0.0.1:5173';

test('the family can look but nothing is written while the feature is off', async ({ browser }: { browser: Browser }) => {
  const adminContext = await browser.newContext({ baseURL: BASE });
  await loginAdminUi(await adminContext.newPage());
  const seed = new AdminSeed(adminContext.request);
  const anonymous = await browser.newContext({ baseURL: BASE });
  const account = await seed.createFamilyWithUser(anonymous.request, `E2E Adultos apagado ${suffix}`, {
    nombre: 'Rosa',
    apellido: `Apagada${suffix}`,
    email: `rosa.ad.off.${suffix}@example.test`,
    password: `Fam-${randomBytes(8).toString('hex')}`,
  });
  const loaded = await seed.createTutor(account.familyId, { nombre: `Laura${suffix}`, apellido: 'Apagada' });
  await anonymous.close();

  const context = await browser.newContext({ baseURL: BASE });
  const page = await context.newPage();
  await page.goto('/login');
  await page.getByLabel(/^Correo electrónico/).fill(account.email);
  await page.getByLabel(/^Contraseña/).fill(account.password);
  await page.getByRole('button', { name: 'Ingresar' }).click();
  await expect(page).toHaveURL(/\/familia$/);

  await page.goto('/familia/adultos');
  await expect(page.getByRole('heading', { level: 1, name: 'Otros adultos' })).toBeVisible();
  await expect(page.getByText(/todavía no está disponible/)).toBeVisible();
  await expect(page.getByText(`Laura${suffix} Apagada`)).toBeVisible();
  await expect(page.getByText('Cargado por el club')).toBeVisible();
  await expect(page.getByRole('button', { name: /Agregar|Editar|Guardar/ })).toHaveCount(0);

  const state = (await (await context.request.get('/api/familia/tutores')).json()) as { habilitado: boolean; adultos: { id: string; version: string }[] };
  expect(state.habilitado).toBe(false);
  expect(state.adultos).toHaveLength(1);
  const headers = { ...(await csrfHeaders(context.request)), 'Idempotency-Key': randomUUID() };
  const create = await context.request.post('/api/familia/tutores', { data: { nombre: 'Rosa', apellido: 'Apagada' }, headers });
  expect(create.status()).toBe(503);
  expect(((await create.json()) as { codigo: string }).codigo).toBe('AUTOSERVICIO_TUTORES_DESHABILITADO');
  const edit = await context.request.put(`/api/familia/tutores/${loaded.id}`, {
    data: { nombre: 'Laura', apellido: 'Apagada', versionEsperada: state.adultos[0]!.version },
    headers: await csrfHeaders(context.request),
  });
  expect(edit.status()).toBe(503);
  expect(((await edit.json()) as { codigo: string }).codigo).toBe('AUTOSERVICIO_TUTORES_DESHABILITADO');
  await context.close();
  await adminContext.close();
});
