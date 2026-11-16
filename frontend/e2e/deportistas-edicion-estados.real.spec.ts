import { randomBytes } from 'node:crypto';
import { expect, test, type BrowserContext, type Page, type Request } from '@playwright/test';
import { AdminSeed, csrfHeaders, loginAdminUi, type SeededFamily } from './support/seed';

/**
 * REAL flows of editing and changing the state of an athlete (FE-05 / U8): browser -> Vite proxy -> Spring backend -> PostgreSQL,
 * against the optimistic-revision contract of FE-05/U6 (PUT /api/admin/deportistas/{id}, POST .../activar and .../desactivar).
 * Requires the administrator enrolled by auth.real.spec.ts (secret in e2e/.state.json). Everything is synthetic and created through
 * the real admin API. The «other administrator» of the conflict tests is the same administrator writing through the API while the
 * browser holds an older revision: for the backend that is the same thing (a revision that moved on). School isolation between two
 * schools is covered by the backend DB tests.
 *
 * The administrator signs in ONCE for the whole file (one sign-in costs one TOTP window). Only the tests labelled «simulated outage»
 * use page.route. No traces, screenshots or videos are recorded (see playwright.config.ts): names, DNIs, phones and e-mails never
 * reach artifacts.
 */
test.describe.configure({ mode: 'serial' });

const suffix = randomBytes(3).toString('hex');
const eightDigitDni = (): string => `6${String(randomBytes(4).readUInt32BE() % 10_000_000).padStart(7, '0')}`;

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

let adminContext: BrowserContext;
let page: Page;
let seed: AdminSeed;
let intruder: SeededFamily;

const consoleLines: string[] = [];
const visitedUrls: string[] = [];

const PHONE = '11 4444-0187';
const PII: string[] = [PHONE];

interface Stored {
  id: string;
  dni: string;
  cuil: string | null;
  nombre: string;
  apellido: string;
  fechaNacimiento: string | null;
  nacionalidad: string | null;
  domicilio: string | null;
  otrosDatosDomicilio: string | null;
  localidad: string | null;
  partido: string | null;
  codigoPostal: string | null;
  telefonoContacto: string | null;
  emailFederativo: string | null;
  activo: boolean;
  version: string;
}

const FIELD_KEYS = ['dni', 'nombre', 'apellido', 'cuil', 'fechaNacimiento', 'nacionalidad', 'domicilio', 'otrosDatosDomicilio', 'localidad', 'partido', 'codigoPostal', 'telefonoContacto', 'emailFederativo'] as const;

/** What the backend really has, read through the admin API. */
async function stored(id: string): Promise<Stored> {
  const response = await adminContext.request.get(`/api/admin/deportistas/${id}`);
  expect(response.ok()).toBe(true);
  return (await response.json()) as Stored;
}

/** «Another administrator»: a PUT through the API with the CURRENT revision, which moves the revision on. */
async function otherAdminEdits(id: string, change: Partial<Record<(typeof FIELD_KEYS)[number], string | null>>): Promise<void> {
  const current = await stored(id);
  const body = Object.fromEntries(FIELD_KEYS.map((key) => [key, current[key]]));
  const response = await adminContext.request.put(`/api/admin/deportistas/${id}`, {
    data: { ...body, ...change, versionEsperada: current.version },
    headers: await csrfHeaders(adminContext.request),
  });
  expect(response.status()).toBe(200);
}

async function otherAdminSetsState(id: string, action: 'activar' | 'desactivar'): Promise<void> {
  const current = await stored(id);
  const response = await adminContext.request.post(`/api/admin/deportistas/${id}/${action}`, {
    data: { versionEsperada: current.version },
    headers: await csrfHeaders(adminContext.request),
  });
  expect(response.status()).toBe(200);
}

/** What belongs to the LINK between a family and the athlete (the response also echoes the athlete's own state, which does change). */
async function linksOf(id: string): Promise<string> {
  const response = await adminContext.request.get(`/api/admin/deportistas/${id}/familias`);
  expect(response.ok()).toBe(true);
  const links = (await response.json()) as Record<string, unknown>[];
  expect(links.length).toBeGreaterThan(0);
  return JSON.stringify(links.map((link) => ({ vinculoId: link.vinculoId, familiaId: link.familiaId, deportistaId: link.deportistaId, estado: link.estado, esPrincipal: link.esPrincipal, autorizadoEn: link.autorizadoEn })));
}
const noHorizontalOverflow = () => page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth);
const label = (pattern: RegExp) => page.getByLabel(pattern);
const status = () => page.getByRole('region', { name: 'Estado' });

async function newAthlete(tag: string, extra: Record<string, string> = {}): Promise<{ id: string; dni: string; nombre: string; apellido: string }> {
  const dni = eightDigitDni();
  const nombre = `Nombre${tag}`;
  const apellido = `Ape ${tag} ${suffix}`;
  const created = await seed.createAthleteWith({ dni, nombre, apellido, ...extra });
  PII.push(dni, nombre, apellido); // none of these may ever reach an address, the storage or the console
  return { id: created.id, dni, nombre, apellido };
}

/** Records the bodies of every PUT or state POST the browser sends for an athlete. */
function recordWrites(id: string): string[] {
  const bodies: string[] = [];
  const onRequest = (request: Request) => {
    const path = new URL(request.url()).pathname;
    if (request.method() !== 'GET' && path.startsWith(`/api/admin/deportistas/${id}`)) bodies.push(`${request.method()} ${path.split(id)[1] ?? ''} ${request.postData() ?? ''}`);
  };
  page.on('request', onRequest);
  return bodies;
}

test.beforeAll(async ({ browser }) => {
  adminContext = await browser.newContext({ baseURL: 'http://127.0.0.1:5173' });
  page = await adminContext.newPage();
  page.on('console', (message) => consoleLines.push(message.text()));
  page.on('framenavigated', (frame) => visitedUrls.push(frame.url()));
  await loginAdminUi(page);
  seed = new AdminSeed(adminContext.request);

  const anonymous = await browser.newContext({ baseURL: 'http://127.0.0.1:5173' });
  intruder = await seed.createFamilyWithUser(anonymous.request, `E2E ${suffix} usuaria`, {
    nombre: 'Ines',
    apellido: 'Intrusa',
    email: `deportistas.edicion.${suffix}@example.test`,
    password: `Fam-${randomBytes(8).toString('hex')}`,
  });
  await anonymous.close();
});

test.afterAll(async () => {
  await adminContext.close();
});

test.describe('edit an athlete (real backend)', () => {
  test('saves a complete replacement: changes what was edited, clears what was emptied with null, keeps the rest and never touches the state', async () => {
    const dni = eightDigitDni();
    const cuil = syntheticCuil(dni);
    const a = await seed.createAthleteWith({
      dni,
      cuil,
      nombre: 'Edicion',
      apellido: `Completa ${suffix}`,
      fechaNacimiento: '2012-05-04',
      nacionalidad: 'Argentina',
      domicilio: 'Calle Falsa 123',
      otrosDatosDomicilio: 'Piso 2',
      localidad: 'Banfield',
      partido: 'Lomas de Zamora',
      codigoPostal: '1828',
      telefonoContacto: PHONE,
      emailFederativo: `edicion.${suffix}@example.test`,
    });
    const before = await stored(a.id);
    const writes = recordWrites(a.id);
    await page.goto(`/admin/deportistas/${a.id}`);
    await page.getByRole('link', { name: 'Editar deportista' }).click();
    await expect(page.getByRole('heading', { name: 'Editar deportista' })).toBeVisible();
    await expect(label(/^DNI/)).toHaveValue(dni);
    await expect(label(/^CUIL/)).toHaveValue(cuil);
    await expect(page.getByRole('button', { name: 'Guardar cambios' })).toBeDisabled(); // nothing to save yet
    await label(/^Nombre/).fill('Edicion Cambiada');
    await label(/^Teléfono/).fill('');
    await label(/^Otros datos del domicilio/).fill('');
    await expect(page.getByText(/Al guardar se van a quitar estos datos: Otros datos del domicilio, Teléfono\./)).toBeVisible();
    await page.getByRole('button', { name: 'Guardar cambios' }).click();

    await expect(page).toHaveURL(new RegExp(`/admin/deportistas/${a.id}$`));
    await expect(page.getByText('Cambios guardados.')).toBeVisible();
    await expect(page.getByRole('heading', { level: 1, name: `Edicion Cambiada Completa ${suffix}` })).toBeVisible();
    const after = await stored(a.id);
    expect(after).toMatchObject({ nombre: 'Edicion Cambiada', telefonoContacto: null, otrosDatosDomicilio: null, dni, cuil, localidad: 'Banfield', codigoPostal: '1828', fechaNacimiento: '2012-05-04', activo: true });
    expect(Number(after.version)).toBe(Number(before.version) + 1);
    const put = writes.find((entry) => entry.startsWith('PUT'))!;
    const sent = JSON.parse(put.slice(put.indexOf('{'))) as Record<string, unknown>;
    expect(sent.versionEsperada).toBe(before.version); // a decimal string, the revision read when the form opened
    expect(sent.telefonoContacto).toBeNull();
    expect('activo' in sent).toBe(false);
    expect(Object.keys(sent)).toHaveLength(14);
    expect(page.url()).not.toContain(dni);
    page.removeAllListeners('request');
  });

  test('tells a DNI held by an active or an inactive athlete and a duplicate CUIL, each at its field, with nothing changed and the same revision', async () => {
    const mine = await newAthlete('Dup');
    const active = await newAthlete('Act');
    const inactive = await newAthlete('Ina');
    await seed.deactivate(inactive.id);
    const cuilOwnerDni = eightDigitDni();
    const cuil = syntheticCuil(cuilOwnerDni);
    await seed.createAthleteWith({ dni: cuilOwnerDni, cuil, nombre: 'Titular', apellido: `Cuil ${suffix}` });
    const before = await stored(mine.id);

    await page.goto(`/admin/deportistas/${mine.id}/editar`);
    await expect(label(/^Nombre/)).toHaveValue(mine.nombre);
    await label(/^DNI/).fill(active.dni);
    await page.getByRole('button', { name: 'Guardar cambios' }).click();
    await expect(page.getByText('Ya existe otro deportista activo con ese DNI. Revisá el número.')).toBeVisible();
    await expect(label(/^DNI/)).toHaveAttribute('aria-invalid', 'true');
    await label(/^DNI/).fill(inactive.dni);
    await page.getByRole('button', { name: 'Guardar cambios' }).click();
    await expect(page.getByText(/Otro deportista inactivo tiene reservado ese DNI/)).toBeVisible();
    await label(/^DNI/).fill(mine.dni);
    await label(/^CUIL/).fill(cuil);
    await page.getByRole('button', { name: 'Guardar cambios' }).click();
    await expect(page.getByText('Ya existe otro deportista con ese CUIL. Revisá el número.')).toBeVisible();
    await expect(label(/^CUIL/)).toHaveAttribute('aria-invalid', 'true');
    await expect(label(/^Nombre/)).toHaveValue(mine.nombre); // the draft is intact
    expect(await stored(mine.id)).toEqual(before); // nothing was applied and the revision did not move
    await label(/^CUIL/).fill('');
    await label(/^Nombre/).fill('Corregido');
    await page.getByRole('button', { name: 'Guardar cambios' }).click();
    await expect(page.getByText('Cambios guardados.')).toBeVisible();
    expect(await stored(mine.id)).toMatchObject({ nombre: 'Corregido', cuil: null });
  });

  test('a stale revision keeps the draft, shows the differences, takes what the administrator did not touch and demands a choice where both changed the same field; a second concurrent change conflicts again', async () => {
    const a = await newAthlete('Conf', { localidad: 'Banfield', partido: 'Lomas de Zamora' });
    await page.goto(`/admin/deportistas/${a.id}/editar`);
    await expect(label(/^Nombre/)).toHaveValue(a.nombre);
    const opened = await stored(a.id);
    await label(/^Nombre/).fill('Mi Nombre');
    await label(/^Localidad/).fill('Mi Localidad');
    await otherAdminEdits(a.id, { apellido: 'Apellido Ajeno', localidad: 'Su Localidad' }); // revision +1
    const bodies = recordWrites(a.id);
    await page.getByRole('button', { name: 'Guardar cambios' }).click();
    await expect(page.getByRole('alert')).toContainText('Otro administrador pudo haber modificado este deportista');
    expect(bodies).toHaveLength(1);
    expect(bodies[0]).toContain(`"versionEsperada":"${opened.version}"`); // the revision captured when the form opened
    await expect(page.getByRole('button', { name: 'Guardar cambios' })).toBeDisabled();
    await expect(label(/^Nombre/)).toHaveValue('Mi Nombre'); // the draft is in memory
    expect(await stored(a.id)).toMatchObject({ nombre: a.nombre, apellido: 'Apellido Ajeno', localidad: 'Su Localidad' }); // nothing overwritten

    await page.getByRole('button', { name: 'Ver datos vigentes' }).click();
    const review = page.getByRole('group', { name: 'Datos vigentes' });
    const table = review.getByRole('table', { name: 'Comparación de datos' });
    await expect(table.getByRole('row', { name: /Apellido/ })).toContainText('Cambió otra persona');
    await expect(table.getByRole('row', { name: /Nombre/ })).toContainText('Solo cambiaste vos');
    await expect(table.getByRole('row', { name: /Localidad/ })).toContainText('Conflicto');
    const proceed = review.getByRole('button', { name: 'Seguir editando con los datos vigentes' });
    await expect(proceed).toBeDisabled(); // an undecided conflict
    for (const radio of await review.getByRole('radio').all()) await expect(radio).not.toBeChecked();
    await review.getByRole('group', { name: 'Localidad' }).getByRole('radio', { name: /Usar el valor vigente/ }).check();
    await expect(proceed).toBeEnabled();
    await proceed.click();
    await expect(label(/^Nombre/)).toHaveValue('Mi Nombre'); // mine
    await expect(label(/^Apellido/)).toHaveValue('Apellido Ajeno'); // incorporated (never touched)
    await expect(label(/^Localidad/)).toHaveValue('Su Localidad'); // the explicit choice
    expect(bodies).toHaveLength(1); // going on is not sending

    await otherAdminEdits(a.id, { partido: 'Partido Ajeno' }); // a SECOND concurrent change
    await page.getByRole('button', { name: 'Guardar cambios' }).click();
    await expect(page.getByRole('alert')).toContainText('Otro administrador pudo haber modificado este deportista');
    expect(await stored(a.id)).toMatchObject({ nombre: a.nombre, partido: 'Partido Ajeno' }); // still not overwritten
    await page.getByRole('button', { name: 'Ver datos vigentes' }).click();
    await page.getByRole('group', { name: 'Datos vigentes' }).getByRole('button', { name: 'Seguir editando con los datos vigentes' }).click();
    await page.getByRole('button', { name: 'Guardar cambios' }).click();
    await expect(page.getByText('Cambios guardados.')).toBeVisible();
    expect(await stored(a.id)).toMatchObject({ nombre: 'Mi Nombre', apellido: 'Apellido Ajeno', localidad: 'Su Localidad', partido: 'Partido Ajeno', activo: true });
    page.removeAllListeners('request');
  });

  test('a revision moved by a change of STATE is a conflict without personal differences, and the edit goes on without touching the state', async () => {
    const a = await newAthlete('Mov');
    await page.goto(`/admin/deportistas/${a.id}/editar`);
    await expect(label(/^Nombre/)).toHaveValue(a.nombre);
    await label(/^Nombre/).fill('Cambiado');
    await otherAdminSetsState(a.id, 'desactivar');
    await page.getByRole('button', { name: 'Guardar cambios' }).click();
    await page.getByRole('button', { name: 'Ver datos vigentes' }).click();
    const review = page.getByRole('group', { name: 'Datos vigentes' });
    await expect(review).toContainText('no tienen diferencias');
    await review.getByRole('button', { name: 'Seguir editando con los datos vigentes' }).click();
    await page.getByRole('button', { name: 'Guardar cambios' }).click();
    await expect(page.getByText('Cambios guardados.')).toBeVisible();
    expect(await stored(a.id)).toMatchObject({ nombre: 'Cambiado', activo: false }); // the edit did not reactivate it
  });

  test('simulated outage, answer lost AFTER the backend saved: no retry, the verification never claims it was this request and nothing is applied twice', async () => {
    const a = await newAthlete('Perd');
    let attempts = 0;
    await page.route(`**/api/admin/deportistas/${a.id}`, async (route) => {
      if (route.request().method() !== 'PUT') return route.continue();
      attempts += 1;
      await route.fetch(); // the request DOES reach the backend, which saves it...
      return route.abort('failed'); // ...but the browser never sees the answer
    });
    await page.goto(`/admin/deportistas/${a.id}/editar`);
    await expect(label(/^Nombre/)).toHaveValue(a.nombre);
    const before = await stored(a.id);
    await label(/^Nombre/).fill('Guardado Igual');
    await page.getByRole('button', { name: 'Guardar cambios' }).click();
    await expect(page.getByRole('alert')).toContainText('No pudimos confirmar si el cambio se guardó');
    await page.waitForTimeout(1_000);
    expect(attempts).toBe(1); // never retried by itself
    await expect(page.getByRole('button', { name: 'Guardar cambios' })).toBeDisabled();
    await expect(label(/^Nombre/)).toHaveValue('Guardado Igual');
    expect(await stored(a.id)).toMatchObject({ nombre: 'Guardado Igual', version: String(Number(before.version) + 1) }); // it WAS saved, once

    await page.getByRole('button', { name: 'Verificar datos vigentes' }).click();
    const panel = page.getByRole('group', { name: 'Verificación' });
    await expect(panel).toContainText('ya coinciden con tu borrador');
    await expect(panel).toContainText('No podemos saber si fue tu envío u otro cambio');
    await expect(page.getByText('Cambios guardados.')).toHaveCount(0); // only a server-confirmed save says so
    expect(attempts).toBe(1);
    await page.unroute(`**/api/admin/deportistas/${a.id}`);
  });

  test('simulated outage, request that never arrived: the verification sees nothing saved and an explicit retry with the same revision saves it once', async () => {
    const a = await newAthlete('Nunca');
    let attempts = 0;
    await page.route(`**/api/admin/deportistas/${a.id}`, async (route) => {
      if (route.request().method() !== 'PUT') return route.continue();
      attempts += 1;
      return route.abort('failed'); // it never reaches the backend
    });
    await page.goto(`/admin/deportistas/${a.id}/editar`);
    await expect(label(/^Nombre/)).toHaveValue(a.nombre);
    const before = await stored(a.id);
    await label(/^Nombre/).fill('Reintento Explicito');
    await page.getByRole('button', { name: 'Guardar cambios' }).click();
    await expect(page.getByRole('alert')).toContainText('No pudimos confirmar si el cambio se guardó');
    await page.getByRole('button', { name: 'Verificar datos vigentes' }).click();
    const panel = page.getByRole('group', { name: 'Verificación' });
    await expect(panel).toContainText('No vemos cambios guardados al verificar.');
    await expect(panel).toContainText('se rechazaría por revisión obsoleta en lugar de aplicarse dos veces');
    expect(await stored(a.id)).toMatchObject({ nombre: a.nombre, version: before.version });
    await page.unroute(`**/api/admin/deportistas/${a.id}`); // the outage ends
    await panel.getByRole('button', { name: 'Volver a intentar' }).click();
    await page.getByRole('button', { name: 'Guardar cambios' }).click();
    await expect(page.getByText('Cambios guardados.')).toBeVisible();
    expect(attempts).toBe(1);
    expect(await stored(a.id)).toMatchObject({ nombre: 'Reintento Explicito', version: String(Number(before.version) + 1) });
  });

  test('warns before losing a draft by leaving through the menu and lets the administrator keep editing', async () => {
    const a = await newAthlete('Guard');
    await page.goto(`/admin/deportistas/${a.id}/editar`);
    await expect(label(/^Nombre/)).toHaveValue(a.nombre);
    await label(/^Nombre/).fill('Borrador');
    await page.getByRole('navigation', { name: 'Principal' }).getByRole('link', { name: 'Familias' }).click();
    const panel = page.getByRole('group', { name: 'Cambios sin guardar' });
    await expect(panel).toBeVisible();
    await expect(page).toHaveURL(/\/editar$/);
    await panel.getByRole('button', { name: 'Seguir editando' }).click();
    await expect(label(/^Nombre/)).toHaveValue('Borrador');
    await page.getByRole('link', { name: 'Cancelar' }).click();
    await page.getByRole('button', { name: 'Descartar y salir' }).click();
    await expect(page).toHaveURL(new RegExp(`/admin/deportistas/${a.id}$`));
    expect((await stored(a.id)).nombre).toBe(a.nombre);
  });
});

test.describe('change the state of an athlete (real backend)', () => {
  test('deactivates and reactivates from its own detail with an explicit confirmation, touching neither the family link nor the data', async () => {
    const a = await newAthlete('Estado');
    const family = await seed.createFamily(`E2E ${suffix} vinculo estado`);
    await seed.link(family.id, [a.id]);
    const linksBefore = await linksOf(a.id);
    const dataBefore = await stored(a.id);
    const writes = recordWrites(a.id);

    await page.goto(`/admin/deportistas/${a.id}`);
    await expect(status().getByRole('button', { name: 'Desactivar deportista' })).toBeVisible();
    await expect(page.getByRole('button', { name: /Reactivar/ })).toHaveCount(0); // only the action that fits
    await status().getByRole('button', { name: 'Desactivar deportista' }).click();
    const confirm = page.getByRole('group', { name: 'Confirmar desactivación' });
    await expect(confirm).toContainText('no se elimina');
    await confirm.getByRole('button', { name: 'Cancelar' }).click(); // cancelling writes nothing
    expect(writes.filter((entry) => entry.startsWith('POST'))).toHaveLength(0);
    expect(await stored(a.id)).toEqual(dataBefore);

    await status().getByRole('button', { name: 'Desactivar deportista' }).click();
    await page.getByRole('button', { name: 'Sí, desactivar' }).dblclick(); // a double click is one request
    await expect(page.getByText('El deportista quedó inactivo.')).toBeVisible();
    expect(writes.filter((entry) => entry.startsWith('POST'))).toHaveLength(1);
    expect(writes.find((entry) => entry.startsWith('POST'))).toContain(`{"versionEsperada":"${dataBefore.version}"}`);
    const inactive = await stored(a.id);
    expect(inactive).toMatchObject({ activo: false, nombre: dataBefore.nombre, dni: dataBefore.dni, version: String(Number(dataBefore.version) + 1) });
    expect(await linksOf(a.id)).toBe(linksBefore); // links untouched

    // The list only changes with confirmed data: it is gone from «Activos» and present in «Inactivos».
    await page.getByRole('link', { name: 'Volver al listado' }).click();
    const search = page.getByRole('searchbox', { name: 'Buscar por nombre, apellido o DNI' });
    await search.fill(a.apellido);
    await expect(page.getByText('No encontramos deportistas con esos criterios.')).toBeVisible();
    await page.getByLabel('Estado').selectOption('INACTIVOS');
    await expect(page.getByRole('list').getByRole('listitem')).toHaveCount(1);

    // The reactivation is reachable from the inactive athlete's own detail.
    await page.getByRole('list').getByRole('link', { name: `${a.apellido}, ${a.nombre}` }).click();
    await expect(page.getByRole('heading', { level: 1, name: `${a.nombre} ${a.apellido}` })).toBeVisible();
    await expect(page.getByRole('main').getByText('Inactivo', { exact: true })).toHaveCount(2);
    await expect(page.getByRole('button', { name: /^Desactivar/ })).toHaveCount(0);
    await status().getByRole('button', { name: 'Reactivar deportista' }).click();
    await page.getByRole('button', { name: 'Sí, reactivar' }).click();
    await expect(page.getByText('El deportista quedó activo.')).toBeVisible();
    expect(await stored(a.id)).toMatchObject({ activo: true, version: String(Number(dataBefore.version) + 2) });
    expect(await linksOf(a.id)).toBe(linksBefore);
    page.removeAllListeners('request');
  });

  test('a stale revision on a state change is a conflict: nothing is applied, the current data are shown and a new decision uses the current revision', async () => {
    const a = await newAthlete('EstadoViejo');
    await page.goto(`/admin/deportistas/${a.id}`);
    await status().getByRole('button', { name: 'Desactivar deportista' }).click();
    await otherAdminEdits(a.id, { apellido: `Cambio Ajeno ${suffix}` }); // revision +1, after the confirmation opened
    await page.getByRole('button', { name: 'Sí, desactivar' }).click();
    await expect(page.getByRole('alert')).toContainText('Otro administrador pudo haber modificado este deportista.');
    expect((await stored(a.id)).activo).toBe(true);
    await expect(page.getByRole('button', { name: /^(Desactivar|Reactivar) deportista$/ })).toHaveCount(0); // blocked until the current data are seen
    await page.getByRole('button', { name: 'Ver datos vigentes' }).click();
    await expect(page.getByRole('heading', { level: 1, name: `${a.nombre} Cambio Ajeno ${suffix}` })).toBeVisible();
    await status().getByRole('button', { name: 'Desactivar deportista' }).click();
    await page.getByRole('button', { name: 'Sí, desactivar' }).click();
    await expect(page.getByText('El deportista quedó inactivo.')).toBeVisible();
    expect((await stored(a.id)).activo).toBe(false);
  });

  test('simulated outage, answer lost AFTER the state changed: no retry, no resend before verifying, and the verification never presents the state as the result of this request', async () => {
    const a = await newAthlete('EstPerd');
    let attempts = 0;
    await page.route(`**/api/admin/deportistas/${a.id}/desactivar`, async (route) => {
      attempts += 1;
      await route.fetch(); // the backend applies it...
      return route.abort('failed'); // ...but the browser never sees the answer
    });
    await page.goto(`/admin/deportistas/${a.id}`);
    await status().getByRole('button', { name: 'Desactivar deportista' }).click();
    await page.getByRole('button', { name: 'Sí, desactivar' }).click();
    await expect(page.getByRole('alert')).toContainText('No pudimos confirmar si el cambio de estado se aplicó');
    await page.waitForTimeout(1_000);
    expect(attempts).toBe(1);
    await expect(page.getByRole('button', { name: /^(Desactivar|Reactivar) deportista$/ })).toHaveCount(0);
    expect((await stored(a.id)).activo).toBe(false); // it WAS applied
    await page.getByRole('button', { name: 'Verificar estado vigente' }).click();
    await expect(page.getByText(/no podemos saber si el cambio actual fue tu solicitud/)).toBeVisible();
    await expect(page.getByText('El deportista quedó inactivo.')).toHaveCount(0);
    await expect(page.getByRole('button', { name: 'Reactivar deportista' })).toBeVisible(); // a NEW decision about the current state
    expect(attempts).toBe(1);
    await page.unroute(`**/api/admin/deportistas/${a.id}/desactivar`);
  });

  test('simulated outage, request that never arrived: the verification sees no change and an explicit retry applies it once', async () => {
    const a = await newAthlete('EstNunca');
    let attempts = 0;
    await page.route(`**/api/admin/deportistas/${a.id}/desactivar`, (route) => {
      attempts += 1;
      return route.abort('failed');
    });
    await page.goto(`/admin/deportistas/${a.id}`);
    await status().getByRole('button', { name: 'Desactivar deportista' }).click();
    await page.getByRole('button', { name: 'Sí, desactivar' }).click();
    await expect(page.getByRole('alert')).toContainText('No pudimos confirmar si el cambio de estado se aplicó');
    await page.getByRole('button', { name: 'Verificar estado vigente' }).click();
    const panel = page.getByRole('group', { name: 'Verificación' });
    await expect(panel).toContainText('No vemos el cambio aplicado.');
    expect((await stored(a.id)).activo).toBe(true);
    await page.unroute(`**/api/admin/deportistas/${a.id}/desactivar`);
    await panel.getByRole('button', { name: 'Volver a intentar' }).click();
    await page.getByRole('button', { name: 'Sí, desactivar' }).click();
    await expect(page.getByText('El deportista quedó inactivo.')).toBeVisible();
    expect(attempts).toBe(1);
    expect((await stored(a.id)).activo).toBe(false);
  });
});

test.describe('permissions, privacy and layout (real backend)', () => {
  test('anonymous, family and CSRF-less writes are rejected and change nothing', async ({ browser, request }) => {
    const a = await newAthlete('Permisos');
    const before = await stored(a.id);
    const body = { dni: a.dni, nombre: 'Intruso', apellido: a.apellido, versionEsperada: before.version };
    expect((await request.put(`/api/admin/deportistas/${a.id}`, { data: body, headers: await csrfHeaders(request) })).status()).toBe(401);
    expect((await request.post(`/api/admin/deportistas/${a.id}/desactivar`, { data: { versionEsperada: before.version }, headers: await csrfHeaders(request) })).status()).toBe(401);

    const familyContext = await browser.newContext({ baseURL: 'http://127.0.0.1:5173' });
    const familyPage = await familyContext.newPage();
    await familyPage.goto('/login');
    await familyPage.getByLabel(/^Correo electrónico/).fill(intruder.email);
    await familyPage.getByLabel(/^Contraseña/).fill(intruder.password);
    await familyPage.getByRole('button', { name: 'Ingresar' }).click();
    await expect(familyPage).toHaveURL(/\/familia$/);
    const familyHeaders = await csrfHeaders(familyContext.request);
    expect((await familyContext.request.put(`/api/admin/deportistas/${a.id}`, { data: body, headers: familyHeaders })).status()).toBe(403);
    expect((await familyContext.request.post(`/api/admin/deportistas/${a.id}/desactivar`, { data: { versionEsperada: before.version }, headers: familyHeaders })).status()).toBe(403);
    for (const path of [`/admin/deportistas/${a.id}/editar`, `/admin/deportistas/${a.id}`]) {
      await familyPage.goto(path);
      await expect(familyPage.getByRole('heading', { name: 'No tenés permiso para ver esta sección' })).toBeVisible();
    }
    expect(await familyPage.locator('body').innerText()).not.toContain(a.nombre);
    await familyContext.close();

    // The administrator without the CSRF header.
    expect((await adminContext.request.put(`/api/admin/deportistas/${a.id}`, { data: body })).status()).toBe(403);
    expect((await adminContext.request.post(`/api/admin/deportistas/${a.id}/desactivar`, { data: { versionEsperada: before.version } })).status()).toBe(403);
    expect(await stored(a.id)).toEqual(before);
  });

  test('an unknown or malformed id on the edit screen gets the same generic «not found»', async () => {
    for (const path of ['/admin/deportistas/no-es-un-id/editar', '/admin/deportistas/00000000-0000-0000-0000-000000000000/editar']) {
      await page.goto(path);
      await expect(page.getByRole('heading', { name: 'No encontramos este deportista' })).toBeVisible();
    }
  });

  test('the detail, the edit form, the conflict review and the menu fit a 360 px phone without horizontal scrolling', async () => {
    const a = await newAthlete('Movil', {
      localidad: 'Una localidad con un nombre extremadamente largo para forzar el ajuste',
      emailFederativo: `un.correo.federativo.muy.largo.${suffix}@example.test`,
    });
    await page.setViewportSize({ width: 360, height: 740 });
    await page.goto(`/admin/deportistas/${a.id}`);
    await expect(page.getByRole('heading', { level: 1 })).toContainText('NombreMovil');
    expect(await noHorizontalOverflow()).toBe(true);
    await page.goto(`/admin/deportistas/${a.id}/editar`);
    await expect(label(/^Nombre/)).toHaveValue(a.nombre);
    expect(await noHorizontalOverflow()).toBe(true);
    for (const control of [label(/^DNI/), label(/^Nombre/), page.getByRole('button', { name: 'Guardar cambios' })]) {
      expect((await control.boundingBox())?.height ?? 0).toBeGreaterThanOrEqual(44);
    }
    await label(/^Nombre/).fill('Nombre bastante largo para forzar el ajuste de linea en pantallas chicas');
    await label(/^Localidad/).fill('Mi localidad igualmente larga para forzar el ajuste de linea en el conflicto');
    await otherAdminEdits(a.id, { localidad: 'Su localidad igualmente larga para forzar el ajuste de linea en el conflicto', apellido: 'Apellido Ajeno' });
    await page.getByRole('button', { name: 'Guardar cambios' }).click();
    await page.getByRole('button', { name: 'Ver datos vigentes' }).click();
    await expect(page.getByRole('group', { name: 'Datos vigentes' })).toBeVisible();
    expect(await noHorizontalOverflow()).toBe(true);
    await page.goto(`/admin/deportistas/${a.id}`);
    await status().getByRole('button', { name: 'Desactivar deportista' }).click();
    await expect(page.getByRole('group', { name: 'Confirmar desactivación' })).toBeVisible();
    expect(await noHorizontalOverflow()).toBe(true);
    await page.setViewportSize({ width: 1280, height: 720 });
  });

  test('no personal datum ever reached an address, the storage or the console during this file', async () => {
    for (const value of PII) {
      expect(visitedUrls.join(' '), value).not.toContain(value);
      expect(visitedUrls.join(' '), value).not.toContain(encodeURIComponent(value));
      expect(consoleLines.join('\n'), value).not.toContain(value);
    }
    // Only ids and fixed route words may follow «deportistas» in an address: never a name, a DNI or any other datum.
    const allowedSegment = /^(nuevo|editar|[0-9a-f-]{36}|no-es-un-id|00000000-0000-0000-0000-000000000000)$/;
    for (const url of visitedUrls) {
      const segments = new URL(url).pathname.split('/').filter(Boolean);
      const at = segments.indexOf('deportistas');
      if (at >= 0) for (const segment of segments.slice(at + 1)) expect(segment, url).toMatch(allowedSegment);
    }
    expect(await page.evaluate(() => JSON.stringify({ ...localStorage, ...sessionStorage }))).not.toContain(PHONE);
  });
});
