import { randomBytes } from 'node:crypto';
import { expect, test, type Browser, type BrowserContext, type Page } from '@playwright/test';
import { AdminSeed, csrfHeaders, loginAdminUi, syntheticDni, type SeededFamily } from './support/seed';

/**
 * REAL flows of U21-BIS C2 (browser -> Vite proxy -> Spring backend -> PostgreSQL): a family loads other adults (no account needed), ADMIN
 * verifies one, observes another with a reason and verifies a third in batch from the inbox, and the family sees the outcome. A verified
 * identity cannot be edited by the family, and another family never sees any of it. All data is synthetic. Needs
 * AUTOSERVICIO_TUTORES_HABILITADO, which the harness turns on only in its disposable base (the shard 3 spec covers the flag off).
 */
test.describe.configure({ mode: 'serial' });

const suffix = randomBytes(3).toString('hex');
const password = () => `Fam-${randomBytes(8).toString('hex')}`;
const BASE = 'http://127.0.0.1:5173';

let browserRef: Browser;
let adminContext: BrowserContext;
let adminPage: Page;
let seed: AdminSeed;
let familyA: SeededFamily;
let familyB: SeededFamily;
const dniVeronica = syntheticDni();
const names = { veronica: `Veronica${suffix}`, gaston: `Gaston${suffix}`, hilda: `Hilda${suffix}` };
const full = (name: string) => `${name} Ayala`;
let veronicaId = '';

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

async function addAdult(page: Page, data: { nombre: string; dni?: string; parentesco?: string }): Promise<void> {
  await page.getByRole('button', { name: 'Agregar otro adulto' }).click();
  await page.getByLabel(/^Nombre/).fill(data.nombre);
  await page.getByLabel(/^Apellido/).fill('Ayala');
  if (data.dni) await page.getByLabel(/^DNI/).fill(data.dni);
  if (data.parentesco) await page.getByLabel(/^Parentesco/).fill(data.parentesco);
  await page.getByRole('button', { name: 'Guardar adulto' }).click();
  await expect(page.getByText(/Listo: cargamos al adulto/)).toBeVisible();
}

const adultRow = (page: Page, name: string) => page.getByRole('listitem').filter({ hasText: full(name) });

test.beforeAll(async ({ browser }) => {
  browserRef = browser;
  adminContext = await browser.newContext({ baseURL: BASE });
  adminPage = await adminContext.newPage();
  await loginAdminUi(adminPage);
  seed = new AdminSeed(adminContext.request);
  const anonymous = await browser.newContext({ baseURL: BASE });
  familyA = await seed.createFamilyWithUser(anonymous.request, `E2E Adultos A ${suffix}`, { nombre: 'Rosa', apellido: `Ayala${suffix}`, email: `rosa.ad.${suffix}@example.test`, password: password() });
  familyB = await seed.createFamilyWithUser(anonymous.request, `E2E Adultos B ${suffix}`, { nombre: 'Beto', apellido: `Barrios${suffix}`, email: `beto.ad.${suffix}@example.test`, password: password() });
  await anonymous.close();
});

test.afterAll(async () => {
  await adminContext.close();
});

test.describe('Otros adultos (real backend)', () => {
  test('a family loads other adults without accounts and they wait for the club review; a duplicate is refused', async () => {
    const { context, page } = await familyPage(familyA);
    await page.getByRole('link', { name: 'Ver otros adultos' }).click();
    await expect(page.getByRole('heading', { level: 1, name: 'Otros adultos' })).toBeVisible();
    await expect(page.getByText(/no le da acceso a los deportistas ni le asigna responsabilidades/).first()).toBeVisible();

    await addAdult(page, { nombre: names.veronica, dni: dniVeronica, parentesco: 'Madre' });
    await addAdult(page, { nombre: names.gaston, parentesco: 'Padre' });
    await addAdult(page, { nombre: names.hilda });
    for (const name of Object.values(names)) {
      await expect(adultRow(page, name).getByText('Pendiente de revisión del club')).toBeVisible();
    }
    await expect(adultRow(page, names.veronica).getByText(/••••/)).toBeVisible();
    expect(await page.content()).not.toContain(dniVeronica);

    // The same DNI again: refused with a fixed message that does not echo the data, and nothing is added.
    await page.getByRole('button', { name: 'Agregar otro adulto' }).click();
    await page.getByLabel(/^Nombre/).fill('Otra');
    await page.getByLabel(/^Apellido/).fill('Persona');
    await page.getByLabel(/^DNI/).fill(dniVeronica);
    await page.getByRole('button', { name: 'Guardar adulto' }).click();
    await expect(page.getByRole('alert')).toContainText('Ya hay un adulto cargado con esos datos en tu familia.');
    await expect(page.getByRole('alert')).not.toContainText(dniVeronica);

    // The API never returns the full DNI, and adding adults granted no access to athletes.
    const state = (await (await context.request.get('/api/familia/tutores')).json()) as { habilitado: boolean; adultos: { id: string; nombre: string }[] };
    expect(state.habilitado).toBe(true);
    expect(state.adultos).toHaveLength(3);
    expect(JSON.stringify(state)).not.toContain(dniVeronica);
    veronicaId = state.adultos.find((a) => a.nombre === names.veronica)!.id;
    const athletes = (await (await context.request.get('/api/familia/deportistas')).json()) as { totalElementos: number };
    expect(athletes.totalElementos).toBe(0);
    await context.close();
  });

  test('ADMIN sees the declared data ready to verify, verifies one, observes another with a reason and verifies the third in batch', async () => {
    await adminPage.goto('/admin/tutores/por-verificar');
    await expect(adminPage.getByRole('heading', { level: 1, name: 'Tutores por verificar' })).toBeVisible();
    const row = (name: string) => adminPage.getByRole('listitem').filter({ hasText: full(name) });

    await expect(row(names.veronica)).toContainText(dniVeronica);
    await expect(row(names.veronica)).toContainText('Madre');
    await expect(row(names.veronica).getByRole('link', { name: `Ver la ficha de ${full(names.veronica)}` })).toContainText(`E2E Adultos A ${suffix}`);
    await expect(row(names.veronica)).toContainText('Sin cargar');

    await row(names.veronica).getByRole('button', { name: `Verificar a ${full(names.veronica)}` }).click();
    await expect(adminPage.getByText('Listo: los datos quedaron verificados.')).toBeVisible();
    await expect(row(names.veronica)).toHaveCount(0);

    await row(names.gaston).getByRole('button', { name: `Observar a ${full(names.gaston)}` }).click();
    const form = adminPage.getByRole('form', { name: `Observar los datos de ${full(names.gaston)}` });
    await expect(form.getByText(/La familia va a ver este motivo/)).toBeVisible();
    await form.getByLabel('Qué tiene que corregir la familia').fill('Falta el DNI de esta persona.');
    await form.getByRole('button', { name: 'Enviar observación' }).click();
    await expect(adminPage.getByText('Listo: la familia va a ver tu observación.')).toBeVisible();
    await expect(row(names.gaston)).toHaveCount(0);

    await adminPage.getByRole('checkbox', { name: `Seleccionar a ${full(names.hilda)}` }).check();
    await adminPage.getByRole('button', { name: 'Verificar seleccionados (1)' }).click();
    const results = adminPage.getByRole('region', { name: 'Resultado de la verificación en lote' });
    await expect(results).toContainText(full(names.hilda));
    await expect(results).toContainText('Verificado');

    // The observed one waits for the family in the other filter, with the reason.
    await adminPage.getByLabel('Mostrar').selectOption('OBSERVADO');
    await expect(row(names.gaston)).toContainText('Falta el DNI de esta persona.');
    await expect(adminPage.getByRole('checkbox')).toHaveCount(0);
  });

  test('the family sees the outcome, cannot change a verified identity, and a correction goes back to the club', async () => {
    const { context, page } = await familyPage(familyA);
    await page.goto('/familia/adultos');
    await expect(adultRow(page, names.veronica).getByText('Verificado por el club')).toBeVisible();
    await expect(adultRow(page, names.hilda).getByText('Verificado por el club')).toBeVisible();
    await expect(adultRow(page, names.gaston).getByText('El club pidió una corrección')).toBeVisible();
    await expect(adultRow(page, names.gaston)).toContainText('Falta el DNI de esta persona.');

    // Verified identity: name and DNI are read-only with the explanation; the contact can still change.
    await page.getByRole('button', { name: `Editar los datos de ${full(names.veronica)}` }).click();
    await expect(page.getByText('Estos datos ya los confirmó el club. Para cambiarlos, avisale al club.')).toBeVisible();
    await expect(page.getByLabel(/^Nombre/)).toBeDisabled();
    await page.getByLabel(/^Teléfono/).fill('11 5555-0102');
    await page.getByRole('button', { name: 'Guardar cambios' }).click();
    await expect(page.getByText('Listo: guardamos los cambios.')).toBeVisible();
    await expect(adultRow(page, names.veronica).getByText('Verificado por el club')).toBeVisible();
    // The server refuses an identity change even when the client is bypassed.
    const current = (await (await context.request.get('/api/familia/tutores')).json()) as { adultos: { id: string; version: string }[] };
    const attempt = await context.request.put(`/api/familia/tutores/${veronicaId}`, {
      data: { nombre: 'Otro', apellido: 'Nombre', versionEsperada: current.adultos.find((a) => a.id === veronicaId)!.version },
      headers: await csrfHeaders(context.request),
    });
    expect(attempt.status()).toBe(409);
    expect(((await attempt.json()) as { codigo: string }).codigo).toBe('IDENTIDAD_VERIFICADA');

    // Correcting the observed adult sends it back to the club.
    await page.getByRole('button', { name: `Editar los datos de ${full(names.gaston)}` }).click();
    await page.getByLabel(/^Cambiar el DNI|^DNI/).fill(syntheticDni());
    await page.getByRole('button', { name: 'Guardar cambios' }).click();
    await expect(page.getByText('Listo: guardamos los cambios.')).toBeVisible();
    await expect(adultRow(page, names.gaston).getByText('Pendiente de revisión del club')).toBeVisible();
    await expect(page.getByText('Falta el DNI de esta persona.')).toHaveCount(0);
    await context.close();
  });

  test('another family never sees these adults and cannot reach them by id', async () => {
    const { context, page } = await familyPage(familyB);
    await page.goto('/familia/adultos');
    await expect(page.getByText('Todavía no hay otros adultos cargados en tu familia.')).toBeVisible();
    await expect(page.getByRole('listitem').filter({ hasText: 'Ayala' })).toHaveCount(0);
    const foreign = await context.request.put(`/api/familia/tutores/${veronicaId}`, {
      data: { nombre: 'Intruso', apellido: 'Ajeno', versionEsperada: '0' },
      headers: await csrfHeaders(context.request),
    });
    expect(foreign.status()).toBe(404);
    expect(((await foreign.json()) as { codigo: string }).codigo).toBe('TUTOR_NO_ENCONTRADO');
    const list = (await (await context.request.get('/api/familia/tutores')).json()) as { adultos: unknown[] };
    expect(list.adultos).toHaveLength(0);
    await context.close();
  });
});
