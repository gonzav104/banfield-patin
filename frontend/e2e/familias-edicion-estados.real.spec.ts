import { randomBytes } from 'node:crypto';
import { expect, test, type APIRequestContext, type Browser, type BrowserContext, type Page } from '@playwright/test';
import { AdminSeed, csrfHeaders, loginAdminUi, type SeededFamily } from './support/seed';
import { adminCredentials } from './support/state';

/**
 * REAL flows of the administrative family edit and status changes (FE-05 / U3): browser -> Vite proxy -> Spring backend
 * -> PostgreSQL, against the optimistic-revision contract of FE-05/U1. Requires the administrator enrolled by
 * auth.real.spec.ts (secret in e2e/.state.json). Everything is synthetic and created through the real admin API. The
 * «other administrator» of the conflict tests is the same administrator writing through the API while the browser holds
 * an older revision: for the backend it is the same thing (a revision that moved on). School isolation between two schools
 * is covered by the backend DB tests.
 *
 * Only the test labelled "simulated outage" uses page.route. No traces, screenshots or videos are recorded (see
 * playwright.config.ts): names and drafts never reach artifacts.
 */
test.describe.configure({ mode: 'serial' });

const suffix = randomBytes(3).toString('hex');
const name = (label: string) => `E2E ${label} ${suffix}`;

let browserRef: Browser;
let adminContext: BrowserContext;
let seed: AdminSeed;
let lockout: SeededFamily;
let intruder: SeededFamily;

interface Stored {
  id: string;
  nombreReferencia: string;
  activa: boolean;
  version: string;
}

async function adminPage(): Promise<{ context: BrowserContext; page: Page }> {
  const context = await browserRef.newContext({ baseURL: 'http://127.0.0.1:5173' });
  const page = await context.newPage();
  await loginAdminUi(page);
  return { context, page };
}

/** What the backend really has, read through the admin API. */
async function stored(id: string): Promise<Stored> {
  const response = await adminContext.request.get(`/api/admin/familias/${id}`);
  expect(response.ok()).toBe(true);
  return (await response.json()) as Stored;
}

/** «Another administrator»: a write through the API that moves the revision on. */
async function otherAdminRenames(id: string, version: string, nombreReferencia: string): Promise<void> {
  const response = await adminContext.request.put(`/api/admin/familias/${id}`, {
    data: { nombreReferencia, versionEsperada: version },
    headers: await csrfHeaders(adminContext.request),
  });
  expect(response.status()).toBe(200);
}

const noHorizontalOverflow = (page: Page) => page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth);
const nameBox = (page: Page) => page.getByLabel(/Nombre de referencia/);

async function familyLoginStatus(browser: Browser, user: SeededFamily): Promise<number> {
  const context = await browser.newContext({ baseURL: 'http://127.0.0.1:5173' });
  try {
    const headers = await csrfHeaders(context.request);
    const response = await context.request.post('/api/auth/login', { data: { email: user.email, password: user.password }, headers });
    return response.status();
  } finally {
    await context.close();
  }
}

test.beforeAll(async ({ browser }) => {
  browserRef = browser;
  adminContext = await browser.newContext({ baseURL: 'http://127.0.0.1:5173' });
  await loginAdminUi(await adminContext.newPage());
  seed = new AdminSeed(adminContext.request);
  const anonymous = await browser.newContext({ baseURL: 'http://127.0.0.1:5173' });
  lockout = await seed.createFamilyWithUser(anonymous.request, name('Acceso'), {
    nombre: 'Rita',
    apellido: 'Acceso',
    email: `acceso.${suffix}@example.test`,
    password: `Fam-${randomBytes(8).toString('hex')}`,
  });
  intruder = await seed.createFamilyWithUser(anonymous.request, name('Intrusa'), {
    nombre: 'Ines',
    apellido: 'Intrusa',
    email: `intrusa.${suffix}@example.test`,
    password: `Fam-${randomBytes(8).toString('hex')}`,
  });
  await anonymous.close();
});

test.afterAll(async () => {
  await adminContext.close();
});

test.describe('edit the name (real backend)', () => {
  test('saves with the real revision, which moves forward, and the list shows the new name', async () => {
    const family = await seed.createFamily(name('Editar'));
    const { context, page } = await adminPage();
    const puts: string[] = [];
    page.on('request', (request) => {
      if (request.method() === 'PUT') puts.push(request.postData() ?? '');
    });
    await page.goto(`/admin/familias/${family.id}`);
    await page.getByRole('button', { name: 'Editar nombre' }).click();
    await expect(nameBox(page)).toHaveValue(name('Editar'));
    await expect(page.getByRole('button', { name: 'Guardar cambios' })).toBeDisabled();
    await nameBox(page).fill(`  ${name('Editada')}  `);
    await page.getByRole('button', { name: 'Guardar cambios' }).click();

    await expect(page.getByRole('heading', { level: 1, name: name('Editada') })).toBeVisible();
    await expect(page.getByText('Nombre actualizado.')).toBeVisible();
    expect(puts).toHaveLength(1);
    expect(JSON.parse(puts[0]!)).toEqual({ nombreReferencia: name('Editada'), versionEsperada: '0' });
    expect(await stored(family.id)).toMatchObject({ nombreReferencia: name('Editada'), version: '1' });

    await page.getByRole('link', { name: 'Volver al listado' }).click();
    await page.getByRole('searchbox', { name: 'Buscar por nombre' }).fill(name('Editada'));
    await expect(page.getByRole('link', { name: name('Editada') })).toBeVisible();
    await context.close();
  });

  test('a stale edit is rejected by the real backend, the draft survives and nothing is overwritten', async () => {
    const family = await seed.createFamily(name('Conflicto'));
    const { context, page } = await adminPage();
    const puts: string[] = [];
    page.on('request', (request) => {
      if (request.method() === 'PUT') puts.push(request.postData() ?? '');
    });
    await page.goto(`/admin/familias/${family.id}`);
    await page.getByRole('button', { name: 'Editar nombre' }).click();
    await nameBox(page).fill(name('Mi borrador'));
    await otherAdminRenames(family.id, '0', name('Del otro admin')); // the revision moves on behind the browser's back

    await page.getByRole('button', { name: 'Guardar cambios' }).click();
    await expect(page.getByRole('alert')).toContainText('Otro administrador modificó esta familia');
    await expect(nameBox(page)).toHaveValue(name('Mi borrador'));
    expect(await stored(family.id)).toMatchObject({ nombreReferencia: name('Del otro admin'), version: '1' }); // not overwritten
    await page.waitForTimeout(1_000);
    expect(puts).toHaveLength(1); // no automatic retry

    await page.getByRole('button', { name: 'Ver datos vigentes' }).click();
    const panel = page.getByRole('group', { name: 'Datos vigentes' });
    await expect(panel).toContainText(name('Del otro admin'));
    await expect(panel).toContainText(name('Mi borrador'));
    expect(puts).toHaveLength(1); // looking is not writing
    expect((await stored(family.id)).nombreReferencia).toBe(name('Del otro admin'));

    await page.getByRole('button', { name: 'Seguir editando mi borrador' }).click();
    await page.getByRole('button', { name: 'Guardar cambios' }).click();
    await expect(page.getByRole('heading', { level: 1, name: name('Mi borrador') })).toBeVisible();
    expect(puts).toHaveLength(2);
    expect(JSON.parse(puts[1]!)).toEqual({ nombreReferencia: name('Mi borrador'), versionEsperada: '1' }); // only after the explicit choice
    expect(await stored(family.id)).toMatchObject({ nombreReferencia: name('Mi borrador'), version: '2' });
    await context.close();
  });

  test('discarding the draft after a conflict keeps the current data and writes nothing', async () => {
    const family = await seed.createFamily(name('Descarte'));
    const { context, page } = await adminPage();
    await page.goto(`/admin/familias/${family.id}`);
    await page.getByRole('button', { name: 'Editar nombre' }).click();
    await nameBox(page).fill(name('Se descarta'));
    await otherAdminRenames(family.id, '0', name('Vigente'));
    await page.getByRole('button', { name: 'Guardar cambios' }).click();
    await page.getByRole('button', { name: 'Ver datos vigentes' }).click();
    await page.getByRole('button', { name: 'Descartar mi borrador' }).click();
    await expect(page.getByRole('heading', { level: 1, name: name('Vigente') })).toBeVisible();
    expect(await page.locator('body').innerText()).not.toContain(name('Se descarta'));
    expect(await stored(family.id)).toMatchObject({ nombreReferencia: name('Vigente'), version: '1' });
    await context.close();
  });

  test('simulated outage: an uncertain result is not retried by itself and keeps the draft', async () => {
    const family = await seed.createFamily(name('Incierta'));
    const { context, page } = await adminPage();
    let attempts = 0;
    await page.route(`**/api/admin/familias/${family.id}`, async (route) => {
      if (route.request().method() === 'PUT') {
        attempts += 1;
        await route.abort('failed'); // the request never reaches the backend
      } else {
        await route.continue();
      }
    });
    await page.goto(`/admin/familias/${family.id}`);
    await page.getByRole('button', { name: 'Editar nombre' }).click();
    await nameBox(page).fill(name('No llegó'));
    await page.getByRole('button', { name: 'Guardar cambios' }).click();
    await expect(page.getByRole('alert')).toContainText('No pudimos confirmar si el cambio se guardó');
    await page.waitForTimeout(1_000);
    expect(attempts).toBe(1);
    await expect(nameBox(page)).toHaveValue(name('No llegó'));
    await page.getByRole('button', { name: 'Verificar datos vigentes' }).click();
    await expect(page.getByRole('group', { name: 'Datos vigentes' })).toContainText(name('Incierta'));
    expect((await stored(family.id)).nombreReferencia).toBe(name('Incierta'));
    await context.close();
  });

  test('an empty name is explained and nothing is sent', async () => {
    const family = await seed.createFamily(name('Vacio'));
    const { context, page } = await adminPage();
    const puts: string[] = [];
    page.on('request', (request) => {
      if (request.method() === 'PUT') puts.push(request.url());
    });
    await page.goto(`/admin/familias/${family.id}`);
    await page.getByRole('button', { name: 'Editar nombre' }).click();
    await nameBox(page).fill('   ');
    await page.getByRole('button', { name: 'Guardar cambios' }).click();
    await expect(page.getByText('Ingresá un nombre de referencia.')).toBeVisible();
    expect(puts).toHaveLength(0);
    await context.close();
  });
});

test.describe('unsaved changes (real browser)', () => {
  test('warns before leaving through a link, lets the user stay, and asks the browser before closing the tab', async () => {
    const family = await seed.createFamily(name('SinGuardar'));
    const { context, page } = await adminPage();
    const consoleText: string[] = [];
    page.on('console', (message) => consoleText.push(message.text()));
    await page.goto(`/admin/familias/${family.id}`);
    await page.getByRole('button', { name: 'Editar nombre' }).click();
    await nameBox(page).fill(name('Borrador'));

    await page.getByRole('link', { name: 'Volver al listado' }).click();
    const panel = page.getByRole('group', { name: 'Cambios sin guardar' });
    await expect(panel).toContainText('Tenés cambios sin guardar.');
    await expect(page).toHaveURL(new RegExp(`/admin/familias/${family.id}$`));
    await panel.getByRole('button', { name: 'Seguir editando' }).click();
    await expect(nameBox(page)).toHaveValue(name('Borrador'));

    // The browser's confirmation arrives AFTER close() resolves, so it is awaited as an event (checked in isolation first).
    const confirmation = page.waitForEvent('dialog');
    void page.close({ runBeforeUnload: true });
    const dialog = await confirmation;
    expect(dialog.type()).toBe('beforeunload');
    await dialog.dismiss(); // «stay on the page»
    expect(page.isClosed()).toBe(false);
    expect(consoleText.join('\n')).not.toContain(suffix);
    expect((await stored(family.id)).nombreReferencia).toBe(name('SinGuardar')); // nothing was written
    await context.close();
  });

  test('the draft, the name and the confirmation never reach the address or storage', async () => {
    const family = await seed.createFamily(name('Privada'));
    const { context, page } = await adminPage();
    await page.goto(`/admin/familias/${family.id}`);
    await page.getByRole('button', { name: 'Editar nombre' }).click();
    await nameBox(page).fill(name('Borrador privado'));
    expect(page.url()).not.toContain(suffix);
    expect(await page.evaluate(() => JSON.stringify({ ...localStorage, ...sessionStorage }))).not.toContain(suffix);
    await context.close();
  });
});

test.describe('activate and deactivate (real backend)', () => {
  test('deactivating asks first, blocks the family’s access for real, changes nothing else, and activating gives it back', async ({ browser }) => {
    const { context, page } = await adminPage();
    await page.goto(`/admin/familias/${lockout.familyId}`);
    expect(await familyLoginStatus(browser, lockout)).toBe(200); // before: the family can sign in

    await page.getByRole('button', { name: 'Desactivar familia' }).click();
    const panel = page.getByRole('group', { name: 'Confirmar desactivación' });
    await expect(panel).toContainText('dejarán de poder ingresar al portal');
    await expect(panel).toContainText('No se elimina su historial');
    await panel.getByRole('button', { name: 'Cancelar' }).click();
    expect((await stored(lockout.familyId)).activa).toBe(true); // cancelling wrote nothing

    await page.getByRole('button', { name: 'Desactivar familia' }).click();
    await page.getByRole('button', { name: 'Sí, desactivar' }).click();
    await expect(page.getByText('La familia quedó desactivada.')).toBeVisible();
    await expect(page.getByText('Inactiva', { exact: true })).toBeVisible();
    expect(await stored(lockout.familyId)).toMatchObject({ activa: false, nombreReferencia: lockout.name, version: '1' });
    expect(await familyLoginStatus(browser, lockout)).toBe(401); // the claim made in the confirmation is true

    await page.getByRole('button', { name: 'Activar familia' }).click();
    const activate = page.getByRole('group', { name: 'Confirmar activación' });
    await expect(activate).toContainText('podrán volver a ingresar al portal');
    await activate.getByRole('button', { name: 'Sí, activar' }).click();
    await expect(page.getByText('La familia quedó activa.')).toBeVisible();
    expect(await stored(lockout.familyId)).toMatchObject({ activa: true, version: '2' });
    expect(await familyLoginStatus(browser, lockout)).toBe(200);
    await context.close();
  });

  test('a stale state change is rejected by the real backend, nothing is applied and nothing is retried', async () => {
    const family = await seed.createFamily(name('EstadoViejo'));
    const { context, page } = await adminPage();
    const posts: string[] = [];
    page.on('request', (request) => {
      if (request.method() === 'POST' && /\/(activar|desactivar)$/.test(request.url())) posts.push(request.postData() ?? '');
    });
    await page.goto(`/admin/familias/${family.id}`);
    await page.getByRole('button', { name: 'Desactivar familia' }).click();
    await otherAdminRenames(family.id, '0', name('Estado nuevo nombre')); // the revision moves on while the confirmation is open
    await page.getByRole('button', { name: 'Sí, desactivar' }).click();

    await expect(page.getByRole('alert')).toContainText('Otro administrador modificó esta familia');
    expect((await stored(family.id)).activa).toBe(true); // nothing applied
    await page.waitForTimeout(1_000);
    expect(posts).toEqual([JSON.stringify({ versionEsperada: '0' })]);

    await page.getByRole('button', { name: 'Ver datos vigentes' }).click();
    await expect(page.getByRole('heading', { level: 1, name: name('Estado nuevo nombre') })).toBeVisible();
    expect(posts).toHaveLength(1);
    await page.getByRole('button', { name: 'Desactivar familia' }).click();
    await page.getByRole('button', { name: 'Sí, desactivar' }).click();
    await expect(page.getByText('La familia quedó desactivada.')).toBeVisible();
    expect(JSON.parse(posts[1]!)).toEqual({ versionEsperada: '1' });
    expect(await stored(family.id)).toMatchObject({ activa: false, version: '2' });
    await context.close();
  });

  test('a double click on the confirmation changes the state once', async () => {
    const family = await seed.createFamily(name('DobleClic'));
    const { context, page } = await adminPage();
    await page.goto(`/admin/familias/${family.id}`);
    await page.getByRole('button', { name: 'Desactivar familia' }).click();
    await page.getByRole('button', { name: 'Sí, desactivar' }).dblclick();
    await expect(page.getByText('La familia quedó desactivada.')).toBeVisible();
    expect(await stored(family.id)).toMatchObject({ activa: false, version: '1' });
    await context.close();
  });
});

test.describe('permissions (real backend)', () => {
  test('anonymous, family, CSRF-less and password-only (MFA pending) writes are all rejected and change nothing', async ({ browser, request }) => {
    const target = await seed.createFamily(name('Protegida'));
    const url = `/api/admin/familias/${target.id}`;
    const body = { nombreReferencia: name('Hackeada'), versionEsperada: '0' };

    // Anonymous.
    expect((await request.put(url, { data: body, headers: await csrfHeaders(request) })).status()).toBe(401);
    expect((await request.post(`${url}/desactivar`, { data: { versionEsperada: '0' }, headers: await csrfHeaders(request) })).status()).toBe(401);

    // A family account.
    const familyContext = await browser.newContext({ baseURL: 'http://127.0.0.1:5173' });
    const login = await familyContext.request.post('/api/auth/login', { data: { email: intruder.email, password: intruder.password }, headers: await csrfHeaders(familyContext.request) });
    expect(login.status()).toBe(200);
    const familyHeaders = await csrfHeaders(familyContext.request);
    expect((await familyContext.request.put(url, { data: body, headers: familyHeaders })).status()).toBe(403);
    expect((await familyContext.request.post(`${url}/desactivar`, { data: { versionEsperada: '0' }, headers: familyHeaders })).status()).toBe(403);
    await familyContext.close();

    // The administrator without the CSRF header.
    expect((await adminContext.request.put(url, { data: body })).status()).toBe(403);
    expect((await adminContext.request.post(`${url}/desactivar`, { data: { versionEsperada: '0' } })).status()).toBe(403);

    // The administrator who only passed the password (second factor still pending).
    const pending: APIRequestContext = (await browser.newContext({ baseURL: 'http://127.0.0.1:5173' })).request;
    const { email, password } = adminCredentials();
    expect((await pending.post('/api/auth/admin/login', { data: { email, password }, headers: await csrfHeaders(pending) })).ok()).toBe(true);
    expect((await pending.put(url, { data: body, headers: await csrfHeaders(pending) })).status()).toBe(403);
    expect((await pending.post(`${url}/desactivar`, { data: { versionEsperada: '0' }, headers: await csrfHeaders(pending) })).status()).toBe(403);

    expect(await stored(target.id)).toMatchObject({ nombreReferencia: name('Protegida'), activa: true, version: '0' });
  });

  test('a family account sees no admin screen to edit', async ({ browser }) => {
    const target = await seed.createFamily(name('Pantalla'));
    const context = await browser.newContext({ baseURL: 'http://127.0.0.1:5173' });
    const page = await context.newPage();
    await page.goto('/login');
    await page.getByLabel(/^Correo electrónico/).fill(intruder.email);
    await page.getByLabel(/^Contraseña/).fill(intruder.password);
    await page.getByRole('button', { name: 'Ingresar' }).click();
    await expect(page).toHaveURL(/\/familia$/);
    await page.goto(`/admin/familias/${target.id}`);
    await expect(page.getByRole('heading', { name: 'No tenés permiso para ver esta sección' })).toBeVisible();
    await expect(page.getByRole('button', { name: /Editar nombre|Desactivar familia/ })).toHaveCount(0);
    await context.close();
  });
});

test.describe('layout (real backend)', () => {
  test('the editor and the confirmation fit a 360 px phone without horizontal scrolling', async () => {
    const family = await seed.createFamily(name('Movil'));
    const { context, page } = await adminPage();
    await page.setViewportSize({ width: 360, height: 740 });
    await page.goto(`/admin/familias/${family.id}`);
    await expect(page.getByRole('heading', { level: 1, name: name('Movil') })).toBeVisible();
    expect(await noHorizontalOverflow(page)).toBe(true);

    await page.getByRole('button', { name: 'Editar nombre' }).click();
    await nameBox(page).fill(`${name('Movil')} con un nombre bastante más largo para forzar el ajuste de línea en pantallas chicas`);
    await expect(page.getByRole('button', { name: 'Guardar cambios' })).toBeVisible();
    expect(await noHorizontalOverflow(page)).toBe(true);
    for (const control of [nameBox(page), page.getByRole('button', { name: 'Guardar cambios' }), page.getByRole('button', { name: 'Cancelar' })]) {
      expect((await control.boundingBox())?.height ?? 0).toBeGreaterThanOrEqual(44);
    }
    await page.getByRole('button', { name: 'Cancelar' }).click();
    await page.getByRole('group', { name: 'Cambios sin guardar' }).getByRole('button', { name: 'Descartar cambios' }).click();

    await page.getByRole('button', { name: 'Desactivar familia' }).click();
    await expect(page.getByRole('group', { name: 'Confirmar desactivación' })).toBeVisible();
    expect(await noHorizontalOverflow(page)).toBe(true);
    await context.close();
  });
});
