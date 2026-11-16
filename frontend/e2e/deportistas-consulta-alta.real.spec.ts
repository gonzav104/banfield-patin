import { randomBytes, randomUUID } from 'node:crypto';
import { expect, test, type BrowserContext, type Page, type Request } from '@playwright/test';
import { dbQuery, hasDisposableDb } from './support/db';
import { AdminSeed, csrfHeaders, loginAdminUi, syntheticDni, type SeededFamily } from './support/seed';

/**
 * REAL flows of the administrative athlete screens (FE-05 / U7: list, detail and creation): browser -> Vite proxy -> Spring backend
 * -> PostgreSQL, against the contract of GET/POST /api/admin/deportistas. Requires the administrator enrolled by auth.real.spec.ts
 * (secret in e2e/.state.json). Everything is synthetic and created through the real admin API. School isolation between two schools
 * is covered by the backend DB tests (a second school cannot be created through the admin API).
 *
 * The administrator signs in ONCE for the whole file (one sign-in costs one TOTP window). Only the tests labelled "simulated outage"
 * use page.route. No traces, screenshots or videos are recorded (see playwright.config.ts): names, DNIs, phones and e-mails never
 * reach artifacts.
 */
test.describe.configure({ mode: 'serial' });

const suffix = randomBytes(3).toString('hex');
const base = `E2E ${suffix}`;

/** 8-digit DNIs (the synthetic 9-digit ones of the seed never collide with them) so that a valid CUIL (2 + 8 + check) can be built. */
const eightDigitDni = (): string => `7${String(randomBytes(4).readUInt32BE() % 10_000_000).padStart(7, '0')}`;

/** 11 digits with a valid check digit (the backend rule), for a given 8-digit DNI. */
function syntheticCuil(dni: string): string {
  const weights = [5, 4, 3, 2, 7, 6, 5, 4, 3, 2];
  for (const prefix of ['20', '27', '23', '24']) {
    const ten = `${prefix}${dni}`;
    const sum = [...ten].reduce((total, digit, index) => total + Number(digit) * weights[index]!, 0);
    const rest = 11 - (sum % 11);
    if (rest !== 10) return `${ten}${rest === 11 ? 0 : rest}`;
  }
  throw new Error('no valid CUIL');
}
const dotted = (dni: string) => `${dni.slice(0, 2)}.${dni.slice(2, 5)}.${dni.slice(5)}`;
const hyphenated = (cuil: string) => `${cuil.slice(0, 2)}-${cuil.slice(2, 10)}-${cuil.slice(10)}`;

let adminContext: BrowserContext;
let page: Page;
let seed: AdminSeed;
let intruder: SeededFamily;

const consoleLines: string[] = [];
const visitedUrls: string[] = [];

/** The data of the athlete the detail tests read. */
const FULL = {
  dni: eightDigitDni(),
  nombre: `Lucia ${suffix}`,
  apellido: `Detalle ${suffix}`,
  fechaNacimiento: '2012-05-04',
  nacionalidad: 'Argentina',
  domicilio: 'Calle Falsa 123',
  otrosDatosDomicilio: 'Piso 2, depto B',
  localidad: 'Banfield',
  partido: 'Lomas de Zamora',
  codigoPostal: '1828',
  telefonoContacto: '11 4444-0187',
  emailFederativo: `detalle.${suffix}@example.test`,
};
const FULL_CUIL = syntheticCuil(FULL.dni);
let fullId = '';

interface StoredAthlete {
  id: string;
  dni: string;
  cuil: string | null;
  nombre: string;
  apellido: string;
  fechaNacimiento: string | null;
  emailFederativo: string | null;
  activo: boolean;
  version: string;
}

/** What the backend really has, read through the admin API. */
async function stored(id: string): Promise<StoredAthlete> {
  const response = await adminContext.request.get(`/api/admin/deportistas/${id}`);
  expect(response.ok()).toBe(true);
  return (await response.json()) as StoredAthlete;
}

/** How many athletes the school has that match a search (by last name or DNI prefix), any status. */
async function countFound(search: string): Promise<number> {
  const response = await adminContext.request.get(`/api/admin/deportistas?estado=TODOS&size=100&busqueda=${encodeURIComponent(search)}`);
  expect(response.ok()).toBe(true);
  return ((await response.json()) as { totalElementos: number }).totalElementos;
}

const noHorizontalOverflow = () => page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth);
const label = (pattern: RegExp) => page.getByLabel(pattern);
const mainText = () => page.getByRole('main').innerText();
const maskedOf = (dni: string) => `${'•'.repeat(dni.length - 3)}${dni.slice(-3)}`;

async function fillForm(values: { dni?: string; nombre?: string; apellido?: string; cuil?: string; nacimiento?: string; nacionalidad?: string; telefono?: string; correo?: string; domicilio?: string }): Promise<void> {
  if (values.dni !== undefined) await label(/^DNI/).fill(values.dni);
  if (values.nombre !== undefined) await label(/^Nombre/).fill(values.nombre);
  if (values.apellido !== undefined) await label(/^Apellido/).fill(values.apellido);
  if (values.cuil !== undefined) await label(/^CUIL/).fill(values.cuil);
  if (values.nacimiento !== undefined) await label(/^Fecha de nacimiento/).fill(values.nacimiento);
  if (values.nacionalidad !== undefined) await label(/^Nacionalidad/).fill(values.nacionalidad);
  if (values.telefono !== undefined) await label(/^Teléfono/).fill(values.telefono);
  if (values.correo !== undefined) await label(/^Correo federativo/).fill(values.correo);
  if (values.domicilio !== undefined) await label(/^Domicilio \(opcional\)/).fill(values.domicilio);
}

/** Records the body of every athlete creation the browser sends. */
function recordCreations(): string[] {
  const bodies: string[] = [];
  const onRequest = (request: Request) => {
    if (request.method() === 'POST' && new URL(request.url()).pathname === '/api/admin/deportistas') bodies.push(request.postData() ?? '');
  };
  page.on('request', onRequest);
  return bodies;
}

function countOf(sql: string, key: string): number | null {
  if (!hasDisposableDb()) return null;
  const match = new RegExp(`${key}=(\\d+)`).exec(dbQuery(sql));
  return match ? Number(match[1]) : null;
}
const accounts = () => countOf("SELECT 'USERS=' || count(*) FROM gestion_patin.usuario;", 'USERS');
const invitations = () => countOf("SELECT 'INV=' || count(*) FROM gestion_patin.invitacion;", 'INV');
const links = () => countOf("SELECT 'LINKS=' || count(*) FROM gestion_patin.familia_deportista;", 'LINKS');

test.beforeAll(async ({ browser }) => {
  adminContext = await browser.newContext({ baseURL: 'http://127.0.0.1:5173' });
  page = await adminContext.newPage();
  page.on('console', (message) => consoleLines.push(message.text()));
  page.on('framenavigated', (frame) => visitedUrls.push(frame.url()));
  await loginAdminUi(page);
  seed = new AdminSeed(adminContext.request);
  fullId = (await seed.createAthleteWith({ ...FULL, cuil: FULL_CUIL })).id;

  const anonymous = await browser.newContext({ baseURL: 'http://127.0.0.1:5173' });
  intruder = await seed.createFamilyWithUser(anonymous.request, `${base} usuaria`, {
    nombre: 'Ines',
    apellido: 'Intrusa',
    email: `deportistas.${suffix}@example.test`,
    password: `Fam-${randomBytes(8).toString('hex')}`,
  });
  await anonymous.close();
});

test.afterAll(async () => {
  await adminContext.close();
});

test.describe('athlete list (real backend)', () => {
  test('shows the masked DNI only, filters by status, searches by name and DNI without writing them anywhere', async () => {
    const active = { nombre: 'Ana', apellido: `${suffix} Filtro Activa`, dni: syntheticDni() };
    const inactive = { nombre: 'Ivan', apellido: `${suffix} Filtro Inactivo`, dni: syntheticDni() };
    await seed.createAthleteWith(active);
    const inactiveId = (await seed.createAthleteWith(inactive)).id;
    await seed.deactivate(inactiveId);

    await page.goto('/admin');
    await page.getByRole('link', { name: 'Deportistas' }).click();
    await expect(page).toHaveURL(/\/admin\/deportistas$/);
    await expect(page.getByRole('heading', { name: 'Deportistas' })).toBeVisible();

    const search = page.getByRole('searchbox', { name: 'Buscar por nombre, apellido o DNI' });
    await search.fill(`${suffix} Filtro`);
    const rows = page.getByRole('list').getByRole('listitem');
    await expect(rows).toHaveCount(1); // «Activos» is the default: the inactive one is not listed
    await expect(rows.first()).toContainText(`${suffix} Filtro Activa, Ana`);
    await expect(rows.first()).toContainText(maskedOf(active.dni));
    await expect(rows.first()).toContainText('Activo');
    expect(await mainText()).not.toContain(active.dni); // the full DNI is never in the list
    expect(page.url()).not.toContain(suffix);

    await page.getByLabel('Estado').selectOption('INACTIVOS');
    await expect(rows).toHaveCount(1);
    await expect(rows.first()).toContainText(`${suffix} Filtro Inactivo, Ivan`);
    await expect(rows.first()).toContainText('Inactivo');
    await page.getByLabel('Estado').selectOption('TODOS');
    await expect(rows).toHaveCount(2);
    await expect(page).toHaveURL(/estado=TODOS/);

    await search.fill(active.dni); // by the whole DNI: it travels in the request, never in the address
    await expect(rows).toHaveCount(1);
    await expect(rows.first()).toContainText(`${suffix} Filtro Activa, Ana`);
    expect(page.url()).not.toContain(active.dni);
    await search.fill(`zzz-${suffix}`);
    await expect(page.getByText('No encontramos deportistas con esos criterios.')).toBeVisible();
    await page.getByRole('button', { name: 'Limpiar filtros' }).click();
    await expect(search).toHaveValue('');
    expect(await page.evaluate(() => JSON.stringify({ ...localStorage, ...sessionStorage }))).not.toContain(suffix);
  });

  test('pages through the results, keeps the page when coming back from a detail and offers the first page again when it no longer exists', async () => {
    for (let n = 1; n <= 22; n += 1) await seed.createAthleteWith({ nombre: 'Paginado', apellido: `Pag ${suffix} ${String(n).padStart(2, '0')}`, dni: syntheticDni() });
    await page.goto('/admin/deportistas');
    await page.getByRole('searchbox', { name: 'Buscar por nombre, apellido o DNI' }).fill(`Pag ${suffix}`);
    const rows = page.getByRole('list').getByRole('listitem');
    await expect(rows).toHaveCount(20);
    await expect(page.getByRole('navigation', { name: 'Paginación' })).toContainText('Página 1 de 2 · 22 en total');
    await page.getByRole('button', { name: 'Siguiente' }).click();
    await expect(rows).toHaveCount(2);
    await expect(page).toHaveURL(/\?pagina=2$/);
    await rows.first().getByRole('link').click();
    await expect(page.getByRole('heading', { level: 1 })).toContainText('Paginado');
    await page.getByRole('link', { name: 'Volver al listado' }).click();
    await expect(page).toHaveURL(/\?pagina=2$/); // the page comes back; the search term does not (it is never kept)
    await expect(page.getByRole('searchbox', { name: 'Buscar por nombre, apellido o DNI' })).toHaveValue('');

    await page.goto('/admin/deportistas?pagina=999');
    await expect(page.getByText('Esa página ya no existe.')).toBeVisible();
    await page.getByRole('button', { name: 'Volver a la primera página' }).click();
    await expect(page).toHaveURL(/\/admin\/deportistas$/);
  });

  test('simulated outage, the list fails and recovers with a retry', async () => {
    let fail = true;
    await page.route('**/api/admin/deportistas?**', (route) => (fail ? route.abort('failed') : route.continue()));
    await page.goto('/admin/deportistas');
    await expect(page.getByRole('alert')).toContainText('No pudimos conectar con el servidor');
    fail = false;
    await page.getByRole('button', { name: 'Reintentar' }).click();
    await expect(page.getByRole('alert')).toHaveCount(0);
    await expect(page.getByRole('list').getByRole('listitem').first()).toBeVisible();
    await page.unroute('**/api/admin/deportistas?**');
  });
});

test.describe('athlete detail (real backend)', () => {
  test('shows every datum of the contract with the FULL DNI and CUIL, and nothing that edits or links', async () => {
    await page.goto(`/admin/deportistas/${fullId}`);
    await expect(page.getByRole('heading', { level: 1, name: `${FULL.nombre} ${FULL.apellido}` })).toBeVisible();
    const text = await mainText();
    for (const expected of [FULL.dni, FULL_CUIL, '04/05/2012', FULL.nacionalidad, FULL.telefonoContacto, FULL.emailFederativo, FULL.domicilio, FULL.otrosDatosDomicilio, FULL.localidad, FULL.partido, FULL.codigoPostal]) {
      expect(text, expected).toContain(expected);
    }
    await expect(page.getByRole('main').getByText('Activo')).toBeVisible();
    await expect(page.getByRole('button', { name: /editar|guardar|desactivar|activar/i })).toHaveCount(0);
    await expect(page.getByRole('link', { name: /editar/i })).toHaveCount(0);
    expect(page.url()).not.toContain(FULL.dni);
    expect((await stored(fullId)).version).toMatch(/^[0-9]+$/); // the revision exists and is decimal (not shown)
  });

  test('says «No informado» for what the athlete does not have, and marks an inactive athlete', async () => {
    const minimal = await seed.createAthleteWith({ nombre: 'Minimo', apellido: `Basico ${suffix}`, dni: syntheticDni() });
    await seed.deactivate(minimal.id);
    await page.goto(`/admin/deportistas/${minimal.id}`);
    await expect(page.getByRole('heading', { level: 1, name: `Minimo Basico ${suffix}` })).toBeVisible();
    await expect(page.getByRole('main').getByText('No informado')).toHaveCount(10);
    await expect(page.getByRole('main').getByText('Inactivo')).toBeVisible();
  });

  test('an unknown or malformed id gets one generic «not found» that reveals nothing', async () => {
    for (const path of [`/admin/deportistas/${randomUUID()}`, '/admin/deportistas/no-es-un-id', '/admin/deportistas/..%2Fadmin']) {
      await page.goto(path);
      await expect(page.getByRole('heading', { name: 'No encontramos este deportista' })).toBeVisible();
      await expect(page.getByRole('link', { name: 'Volver al listado' })).toBeVisible();
      expect(await mainText()).not.toMatch(/escuela|DEPORTISTA_NO_ENCONTRADO|404/i);
    }
  });
});

test.describe('new athlete (real backend)', () => {
  test('creates an athlete with every field, normalizes what the backend normalizes and creates no link, account or invitation', async () => {
    const dni = eightDigitDni();
    const cuil = syntheticCuil(dni);
    const before = { accounts: accounts(), invitations: invitations(), links: links() };
    await page.goto('/admin/deportistas');
    await page.getByRole('link', { name: 'Nuevo deportista' }).click();
    await expect(page.getByRole('heading', { name: 'Nuevo deportista' })).toBeVisible();
    await fillForm({ dni: dotted(dni), nombre: `  Nueva ${suffix} `, apellido: 'Completa', cuil: hyphenated(cuil), nacimiento: '2013-08-09', nacionalidad: 'Argentina', telefono: '11 4444-0187', correo: `NUEVA.${suffix}@Example.test`, domicilio: 'Calle Falsa 123' });
    await page.getByRole('button', { name: 'Crear deportista' }).click();

    await expect(page).toHaveURL(/\/admin\/deportistas\/[0-9a-f-]{36}$/);
    await expect(page.getByRole('heading', { level: 1, name: `Nueva ${suffix} Completa` })).toBeVisible();
    await expect(page.getByText('Deportista creado. Todavía no tiene familia asignada.')).toBeVisible();
    const id = page.url().split('/').pop()!;
    const row = await stored(id);
    expect(row).toMatchObject({ dni, cuil, nombre: `Nueva ${suffix}`, apellido: 'Completa', fechaNacimiento: '2013-08-09', emailFederativo: `nueva.${suffix}@example.test`, activo: true, version: '0' });
    expect(await mainText()).toContain(dni); // the detail shows the stored (normalized) DNI
    expect(page.url()).not.toContain(dni);
    if (hasDisposableDb()) expect({ accounts: accounts(), invitations: invitations(), links: links() }).toEqual(before);

    await page.getByRole('link', { name: 'Volver al listado' }).click();
    await page.getByRole('searchbox', { name: 'Buscar por nombre, apellido o DNI' }).fill(`Nueva ${suffix}`);
    await expect(page.getByRole('list').getByRole('listitem')).toHaveCount(1); // the list was refreshed
  });

  test('validates in the browser before sending anything', async () => {
    const bodies = recordCreations();
    await page.goto('/admin/deportistas/nuevo');
    await page.getByRole('button', { name: 'Crear deportista' }).click();
    await expect(page.getByText('Ingresá el DNI.')).toBeVisible();
    await expect(page.getByText('Ingresá el nombre.')).toBeVisible();
    await expect(page.getByText('Ingresá el apellido.')).toBeVisible();
    await expect(label(/^DNI/)).toHaveAttribute('aria-invalid', 'true');
    await fillForm({ dni: '123', nombre: 'A', apellido: 'B', cuil: '20301113228', nacimiento: '2999-01-01', correo: 'sin-arroba' });
    await page.getByRole('button', { name: 'Crear deportista' }).click();
    await expect(page.getByText('El DNI debe tener entre 7 y 9 dígitos.')).toBeVisible();
    await expect(page.getByText('El CUIL debe tener 11 dígitos y un dígito verificador válido.')).toBeVisible();
    await expect(page.getByText('Ingresá un correo electrónico válido.')).toBeVisible();
    expect(bodies).toHaveLength(0);
    page.removeAllListeners('request');
  });

  test('tells a duplicate DNI, a DNI reserved by an inactive athlete and a duplicate CUIL, each at its field, and creates nothing', async () => {
    const activeDni = eightDigitDni();
    const inactiveDni = eightDigitDni();
    const cuilDni = eightDigitDni();
    const cuil = syntheticCuil(cuilDni);
    await seed.createAthleteWith({ nombre: 'Titular', apellido: `Activo ${suffix}`, dni: activeDni });
    const inactive = await seed.createAthleteWith({ nombre: 'Titular', apellido: `Reservado ${suffix}`, dni: inactiveDni });
    await seed.deactivate(inactive.id);
    await seed.createAthleteWith({ nombre: 'Titular', apellido: `Cuil ${suffix}`, dni: cuilDni, cuil });

    await page.goto('/admin/deportistas/nuevo');
    await fillForm({ dni: activeDni, nombre: 'Intento', apellido: `Uno ${suffix}` });
    await page.getByRole('button', { name: 'Crear deportista' }).click();
    await expect(page.getByText('Ya existe un deportista activo con ese DNI.')).toBeVisible();
    await expect(label(/^DNI/)).toHaveAttribute('aria-invalid', 'true');
    await expect(page).toHaveURL(/\/nuevo$/);

    await label(/^DNI/).fill(inactiveDni);
    await page.getByRole('button', { name: 'Crear deportista' }).click();
    await expect(page.getByText(/Ya existe un deportista inactivo con ese DNI y el DNI sigue reservado/)).toBeVisible();
    await expect(page.getByText(/deportista activo con ese DNI/)).toHaveCount(0);

    await label(/^DNI/).fill(eightDigitDni());
    await label(/^CUIL/).fill(cuil);
    await page.getByRole('button', { name: 'Crear deportista' }).click();
    await expect(page.getByText('Ya existe un deportista con ese CUIL.')).toBeVisible();
    await expect(label(/^CUIL/)).toHaveAttribute('aria-invalid', 'true');
    await expect(label(/^Nombre/)).toHaveValue('Intento'); // the draft is intact
    expect(await countFound(`Uno ${suffix}`)).toBe(0);
  });

  test('a double click creates one athlete, not two', async () => {
    const bodies = recordCreations();
    const dni = eightDigitDni();
    await page.goto('/admin/deportistas/nuevo');
    await fillForm({ dni, nombre: 'Doble', apellido: `Clic ${suffix}` });
    await page.getByRole('button', { name: 'Crear deportista' }).dblclick();
    await expect(page).toHaveURL(/\/admin\/deportistas\/[0-9a-f-]{36}$/);
    expect(bodies).toHaveLength(1);
    expect(await countFound(`Clic ${suffix}`)).toBe(1);
    page.removeAllListeners('request');
  });

  test('simulated outage, answer lost AFTER the backend created the athlete: no retry, no claim, the check is only a hint and a retry cannot duplicate it', async () => {
    const dni = eightDigitDni();
    let attempts = 0;
    await page.route('**/api/admin/deportistas', async (route) => {
      if (route.request().method() !== 'POST') return route.continue();
      attempts += 1;
      await route.fetch(); // the request DOES reach the backend, which creates the athlete...
      return route.abort('failed'); // ...but the browser never sees the answer
    });
    await page.goto('/admin/deportistas/nuevo');
    await fillForm({ dni, nombre: 'Perdida', apellido: `Respuesta ${suffix}` });
    await page.getByRole('button', { name: 'Crear deportista' }).click();
    await expect(page.getByRole('alert')).toContainText('No pudimos confirmar si el deportista se creó');
    await page.waitForTimeout(1_000);
    expect(attempts).toBe(1); // never retried by itself
    await expect(page).toHaveURL(/\/nuevo$/);
    await expect(label(/^Nombre/)).toHaveValue('Perdida');
    await expect(page.getByRole('button', { name: 'Crear deportista' })).toBeDisabled();
    expect(await countFound(`Respuesta ${suffix}`)).toBe(1); // it WAS created

    await page.getByRole('button', { name: 'Verificar en el listado' }).click();
    const panel = page.getByRole('group', { name: 'Verificación' });
    await expect(panel).toContainText('no podemos confirmarlo');
    await expect(panel).toContainText(maskedOf(dni));
    expect(await panel.innerText()).not.toContain(dni);
    await expect(page.getByText(/Deportista creado/)).toHaveCount(0); // only a server-confirmed creation says so
    await expect(page.getByRole('button', { name: 'Crear deportista' })).toBeDisabled();

    await page.unroute('**/api/admin/deportistas'); // the outage ends: the explicit retry reaches the backend and sees its real answer
    await panel.getByRole('button', { name: 'Volver a intentar' }).click();
    await page.getByRole('button', { name: 'Crear deportista' }).click();
    await expect(page.getByText(/Ya existe un deportista activo con ese DNI/)).toBeVisible();
    await expect(page.getByText(/es posible que ese deportista sea el que intentaste crear/)).toBeVisible();
    expect(attempts).toBe(1); // only the first attempt went through the route
    expect(await countFound(`Respuesta ${suffix}`)).toBe(1); // never two
  });

  test('simulated outage, request that never arrived: the check only reports that nothing was found, and an explicit retry creates it once', async () => {
    const dni = eightDigitDni();
    let attempts = 0;
    await page.route('**/api/admin/deportistas', async (route) => {
      if (route.request().method() !== 'POST') return route.continue();
      attempts += 1;
      return route.abort('failed'); // it never reaches the backend
    });
    await page.goto('/admin/deportistas/nuevo');
    await fillForm({ dni, nombre: 'Nunca', apellido: `Llego ${suffix}` });
    await page.getByRole('button', { name: 'Crear deportista' }).click();
    await expect(page.getByRole('alert')).toContainText('No pudimos confirmar si el deportista se creó');
    await page.getByRole('button', { name: 'Verificar en el listado' }).click();
    const panel = page.getByRole('group', { name: 'Verificación' });
    await expect(panel).toContainText('No observamos un deportista con ese DNI en este momento.');
    await expect(panel).toContainText('Eso no prueba que la solicitud original no se haya aplicado');
    await expect(panel).not.toContainText('incompleta');
    expect(await countFound(`Llego ${suffix}`)).toBe(0);
    await page.unroute('**/api/admin/deportistas'); // the outage ends
    await panel.getByRole('button', { name: 'Volver a intentar' }).click();
    await page.getByRole('button', { name: 'Crear deportista' }).click();
    await expect(page).toHaveURL(/\/admin\/deportistas\/[0-9a-f-]{36}$/);
    expect(attempts).toBe(1);
    expect(await countFound(`Llego ${suffix}`)).toBe(1);
  });

  test('warns before losing a draft by leaving through the menu, and lets the administrator keep editing', async () => {
    await page.goto('/admin/deportistas/nuevo');
    await fillForm({ nombre: 'Borrador' });
    await page.getByRole('navigation', { name: 'Principal' }).getByRole('link', { name: 'Familias' }).click();
    const panel = page.getByRole('group', { name: 'Cambios sin guardar' });
    await expect(panel).toBeVisible();
    await expect(page).toHaveURL(/\/admin\/deportistas\/nuevo$/);
    await panel.getByRole('button', { name: 'Seguir editando' }).click();
    await expect(label(/^Nombre/)).toHaveValue('Borrador');
    await page.getByRole('link', { name: 'Cancelar' }).click();
    await page.getByRole('button', { name: 'Descartar y salir' }).click();
    await expect(page).toHaveURL(/\/admin\/deportistas$/);
  });
});

test.describe('permissions, privacy and layout (real backend)', () => {
  test('anonymous, family and CSRF-less access are rejected and create or reveal nothing', async ({ browser, request }) => {
    expect((await request.get('/api/admin/deportistas')).status()).toBe(401);
    expect((await request.get(`/api/admin/deportistas/${fullId}`)).status()).toBe(401);
    const body = { dni: eightDigitDni(), nombre: 'Intruso', apellido: `Anonimo ${suffix}` };
    expect((await request.post('/api/admin/deportistas', { data: body, headers: await csrfHeaders(request) })).status()).toBe(401);

    const familyContext = await browser.newContext({ baseURL: 'http://127.0.0.1:5173' });
    const familyPage = await familyContext.newPage();
    await familyPage.goto('/login');
    await familyPage.getByLabel(/^Correo electrónico/).fill(intruder.email);
    await familyPage.getByLabel(/^Contraseña/).fill(intruder.password);
    await familyPage.getByRole('button', { name: 'Ingresar' }).click();
    await expect(familyPage).toHaveURL(/\/familia$/);
    expect((await familyContext.request.get('/api/admin/deportistas')).status()).toBe(403);
    expect((await familyContext.request.get(`/api/admin/deportistas/${fullId}`)).status()).toBe(403);
    const familyHeaders = await csrfHeaders(familyContext.request);
    expect((await familyContext.request.post('/api/admin/deportistas', { data: { ...body, nombre: 'Intrusa' }, headers: familyHeaders })).status()).toBe(403);
    for (const path of ['/admin/deportistas', `/admin/deportistas/${fullId}`, '/admin/deportistas/nuevo']) {
      await familyPage.goto(path);
      await expect(familyPage.getByRole('heading', { name: 'No tenés permiso para ver esta sección' })).toBeVisible();
    }
    expect(await familyPage.locator('body').innerText()).not.toContain(FULL.nombre);
    await familyContext.close();

    // The administrator without the CSRF header.
    expect((await adminContext.request.post('/api/admin/deportistas', { data: { ...body, nombre: 'SinCsrf' } })).status()).toBe(403);
    expect(await countFound(`Anonimo ${suffix}`)).toBe(0);
  });

  test('the list, the detail and the form fit a 360 px phone without horizontal scrolling', async () => {
    const long = await seed.createAthleteWith({
      nombre: 'Nombre bastante largo para forzar el ajuste de linea',
      apellido: `Apellido igualmente extenso para pantallas chicas ${suffix}`,
      dni: syntheticDni(),
      emailFederativo: `un.correo.federativo.muy.largo.${suffix}@example.test`,
      domicilio: 'Una calle con un nombre extremadamente largo numero 12345678901234567890',
    });
    await page.setViewportSize({ width: 360, height: 740 });
    await page.goto('/admin/deportistas');
    await page.getByRole('searchbox', { name: 'Buscar por nombre, apellido o DNI' }).fill(`extenso para pantallas chicas ${suffix}`);
    await expect(page.getByRole('list').getByRole('listitem')).toHaveCount(1);
    expect(await noHorizontalOverflow()).toBe(true);
    await page.goto(`/admin/deportistas/${long.id}`);
    await expect(page.getByRole('heading', { level: 1 })).toContainText('Nombre bastante largo');
    expect(await noHorizontalOverflow()).toBe(true);
    await page.goto('/admin/deportistas/nuevo');
    await fillForm({ nombre: 'Nombre bastante largo para forzar el ajuste de linea', apellido: 'Apellido igualmente extenso para pantallas chicas', correo: `un.correo.muy.largo.${suffix}@example.test` });
    expect(await noHorizontalOverflow()).toBe(true);
    for (const control of [label(/^DNI/), label(/^Nombre/), page.getByRole('button', { name: 'Crear deportista' })]) {
      expect((await control.boundingBox())?.height ?? 0).toBeGreaterThanOrEqual(44);
    }
    await page.setViewportSize({ width: 1280, height: 720 });
  });

  test('no personal datum ever reached an address, the storage or the console during this file', async () => {
    const pii = [FULL.dni, FULL_CUIL, FULL.telefonoContacto, FULL.emailFederativo, FULL.domicilio, FULL.nombre, FULL.apellido, '4444-0187'];
    for (const value of pii) {
      expect(visitedUrls.join(' '), value).not.toContain(value);
      expect(visitedUrls.join(' '), value).not.toContain(encodeURIComponent(value));
      expect(consoleLines.join('\n'), value).not.toContain(value);
    }
    expect(await page.evaluate(() => JSON.stringify({ ...localStorage, ...sessionStorage }))).not.toMatch(new RegExp(pii.map((value) => value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')).join('|')));
  });
});
