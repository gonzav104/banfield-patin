import { randomBytes } from 'node:crypto';
import { expect, test, type Browser, type BrowserContext, type Page } from '@playwright/test';
import { AdminSeed, csrfHeaders, loginAdminUi, type SeededFamily } from './support/seed';

/**
 * REAL flows of U21-BIS C1 (browser -> Vite proxy -> Spring backend -> PostgreSQL): a family user completes their own data, recognises
 * themselves in the people the club already loaded («Soy yo»), and an administrator invites a concrete person whose account is born
 * linked. Declaring data never changes what anyone can see. All data is synthetic. Needs AUTOSERVICIO_PERFIL_HABILITADO, which the
 * harness turns on only in its disposable base (the shard 3 spec covers the flag off).
 */
test.describe.configure({ mode: 'serial' });

const suffix = randomBytes(3).toString('hex');
const password = () => `Fam-${randomBytes(8).toString('hex')}`;
const BASE = 'http://127.0.0.1:5173';

let browserRef: Browser;
let adminContext: BrowserContext;
let seed: AdminSeed;
let familyA: SeededFamily;
let familyB: SeededFamily;
let personName = '';

async function familyPage(account: { email: string; password: string }): Promise<{ context: BrowserContext; page: Page }> {
  const context = await browserRef.newContext({ baseURL: BASE });
  const page = await context.newPage();
  await page.goto('/login');
  await page.getByLabel(/^Correo electrónico/).fill(account.email);
  await page.getByLabel(/^Contraseña/).fill(account.password);
  await page.getByRole('button', { name: 'Ingresar' }).click();
  await expect(page).toHaveURL(/\/familia$/);
  return { context, page };
}

async function register(anonymous: BrowserContext, token: string, user: { nombre: string; apellido: string; email: string; password: string }) {
  const response = await anonymous.request.post('/api/auth/registro/invitacion', { data: { token, ...user }, headers: await csrfHeaders(anonymous.request) });
  expect(response.ok(), `register ${user.email}`).toBe(true);
}

test.beforeAll(async ({ browser }) => {
  browserRef = browser;
  adminContext = await browser.newContext({ baseURL: BASE });
  await loginAdminUi(await adminContext.newPage());
  seed = new AdminSeed(adminContext.request);
  const anonymous = await browser.newContext({ baseURL: BASE });
  familyA = await seed.createFamilyWithUser(anonymous.request, `E2E Perfil A ${suffix}`, { nombre: 'Rosa', apellido: `Ayala${suffix}`, email: `rosa.${suffix}@example.test`, password: password() });
  familyB = await seed.createFamilyWithUser(anonymous.request, `E2E Perfil B ${suffix}`, { nombre: 'Beto', apellido: `Barrios${suffix}`, email: `beto.${suffix}@example.test`, password: password() });
  await anonymous.close();
  personName = `Laura${suffix}`;
  await seed.createTutor(familyA.familyId, { nombre: personName, apellido: 'Ayala', parentesco: 'Madre' });
});

test.afterAll(async () => {
  await adminContext.close();
});

test.describe('Mis datos (real backend)', () => {
  test('a person recognises themselves in the family («Soy yo») and the identity the club loaded stays locked', async () => {
    const { context, page } = await familyPage(familyA);
    await page.getByRole('link', { name: 'Ver mis datos' }).click();
    await expect(page.getByRole('heading', { level: 1, name: 'Mis datos' })).toBeVisible();
    await expect(page.getByRole('heading', { name: '¿Sos alguna de estas personas?' })).toBeVisible();
    const list = page.getByRole('list', { name: 'Personas de tu familia' });
    await expect(list.getByRole('listitem')).toHaveCount(1);
    await list.getByRole('button', { name: new RegExp(`Soy ${personName}`) }).click();

    await expect(page.getByRole('heading', { name: 'Tus datos' })).toBeVisible();
    await expect(page.getByLabel(/^Nombre/)).toHaveValue(personName);
    await expect(page.getByLabel(/^Nombre/)).toBeDisabled();
    await expect(page.getByText(/El club ya verificó tu nombre y tu DNI/)).toBeVisible();
    await expect(page.getByText(/Todavía tiene que confirmar que esta cuenta es tuya/)).toBeVisible();
    // Contact stays editable; the DNI only ever appears masked.
    await page.getByLabel(/^Teléfono/).fill('11 5555-0101');
    await page.getByRole('button', { name: 'Guardar cambios' }).click();
    await expect(page.getByText('Listo: guardamos tus datos.')).toBeVisible();
    expect(await page.content()).not.toMatch(/\b\d{2}\.?\d{3}\.?\d{3}\b/);
    // Declaring did not change what the family can see.
    const athletes = await context.request.get('/api/familia/deportistas');
    expect(((await athletes.json()) as { totalElementos: number }).totalElementos).toBe(0);
    await context.close();
  });

  test('another family never sees that person, and its own user completes the data from scratch', async () => {
    const { context, page } = await familyPage(familyB);
    await page.goto('/familia/mis-datos');
    await expect(page.getByRole('heading', { name: 'Cargá tus datos' })).toBeVisible();
    await expect(page.getByText(personName)).toHaveCount(0);
    await page.getByLabel(/^Nombre/).fill('Beto');
    await page.getByLabel(/^Apellido/).fill(`Barrios${suffix}`);
    await page.getByLabel(/^DNI/).fill('30.111.222');
    await page.getByLabel(/^Correo/).fill(`Beto.${suffix}@Example.Test`);
    await page.getByRole('button', { name: 'Guardar mis datos' }).click();
    await expect(page.getByText(/Listo: tus datos quedaron cargados/)).toBeVisible();
    await expect(page.getByText(/Ahora figura •+222/)).toBeVisible();
    expect(await page.content()).not.toContain('30.111.222');

    // After a reload the data are there, the identity is still editable (nobody verified it yet), and the API never returns the DNI.
    await page.reload();
    await expect(page.getByLabel(/^Nombre/)).toBeEnabled();
    const state = (await (await context.request.get('/api/familia/mi-perfil')).json()) as { perfil: { dniEnmascarado: string; email: string }; personasSinCuenta: unknown[] };
    expect(state.perfil.email).toBe(`beto.${suffix}@example.test`);
    expect(JSON.stringify(state)).not.toContain('30111222');
    expect(state.personasSinCuenta).toHaveLength(0);
    // Editing with a stale revision is refused and nothing is overwritten.
    const stale = await context.request.put('/api/familia/mi-perfil', {
      data: { nombre: 'Otro', apellido: 'Nombre', versionEsperada: '999' },
      headers: await csrfHeaders(context.request),
    });
    expect(stale.status()).toBe(409);
    await context.close();
  });

  test('an administrator invites a concrete person: their account is born linked and sees their data already loaded', async () => {
    const invited = await seed.createTutor(familyA.familyId, { nombre: `Martin${suffix}`, apellido: 'Ayala', parentesco: 'Padre' });
    const email = `martin.${suffix}@example.test`;
    const response = await adminContext.request.post('/api/admin/invitaciones', {
      data: { familiaId: familyA.familyId, tutorId: invited.id, emailSugerido: email },
      headers: await csrfHeaders(adminContext.request),
    });
    expect(response.ok()).toBe(true);
    const { token } = (await response.json()) as { token: string };
    const pass = password();
    const anonymous = await browserRef.newContext({ baseURL: BASE });
    await register(anonymous, token, { nombre: 'Martin', apellido: 'Ayala', email, password: pass });
    await anonymous.close();

    const { context, page } = await familyPage({ email, password: pass });
    await page.goto('/familia/mis-datos');
    await expect(page.getByRole('heading', { name: 'Tus datos' })).toBeVisible();
    await expect(page.getByLabel(/^Nombre/)).toHaveValue(`Martin${suffix}`);
    await expect(page.getByRole('heading', { name: '¿Sos alguna de estas personas?' })).toHaveCount(0);
    // The club invited this very person, so the club did confirm the account (unlike a «Soy yo» claim, tested above).
    await expect(page.getByText('El club confirmó que esta cuenta es tuya.')).toBeVisible();
    // ADMIN sees in the invitation list that the invited person was associated.
    const list = (await (await adminContext.request.get(`/api/admin/invitaciones?familiaId=${familyA.familyId}`)).json()) as { contenido: { tutorId: string | null; asociacionDePersona: string | null }[] };
    expect(list.contenido.find((i) => i.tutorId === invited.id)?.asociacionDePersona).toBe('ASOCIADA');
    // Inviting the same person again is refused: they already have an account.
    const again = await adminContext.request.post('/api/admin/invitaciones', {
      data: { familiaId: familyA.familyId, tutorId: invited.id },
      headers: await csrfHeaders(adminContext.request),
    });
    expect(again.status()).toBe(409);
    await context.close();
  });
});
