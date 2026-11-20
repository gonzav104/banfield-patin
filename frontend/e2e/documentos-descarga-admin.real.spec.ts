import { randomBytes, randomUUID } from 'node:crypto';
import { readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { expect, test, type APIResponse, type Browser, type BrowserContext, type Page, type Response } from '@playwright/test';
import { dbQuery, hasDisposableDb } from './support/db';
import { browserJpeg, sha256, syntheticPdf } from './support/documents';
import { leakPattern } from './support/leak';
import { AdminSeed, csrfHeaders, loginAdminUi, schoolToday, type SeededFamily } from './support/seed';

/**
 * REAL audited download of a document by an ADMIN (FE-07/U16): browser -> Vite proxy -> Spring backend -> PostgreSQL, with the
 * document pipeline ENABLED against the external TEST DOUBLES started by frontend-e2e.sh (support/fakes/storage-fake.ts now also
 * answers the authenticated GET of an object; support/fakes/clamd-fake.ts scans the uploads). The family uploads through the real
 * API exactly as its browser does (multipart `archivo` + `tipo`, CSRF header and a fresh Idempotency-Key; the U15 spec covers the
 * upload screen itself). The administrator then opens the documents from the athlete detail through the real screen: the bytes the
 * browser receives, the object the Storage double holds and the bytes the family sent are compared by SHA-256.
 *
 * The integrity failures are produced by rewriting the object in the Storage double's inspection directory (E2E_STORAGE_OBJECTS_DIR),
 * located through the disposable database (read-only queries): the backend reads the rewritten bytes and must refuse them.
 * The audit trail is read from the same database, read-only. The doubles do NOT replace the opt-in tests against a real Supabase.
 *
 * One athlete per document set: the tampered athlete never shares a document with the one whose files are delivered.
 */
test.describe.configure({ mode: 'serial' });

const OBJECTS_DIR = process.env.E2E_STORAGE_OBJECTS_DIR ?? '';
// Outside frontend-e2e.sh (no doubles, upload disabled) this file skips EXPLICITLY; in CI a missing setup is a failure.
test.skip(OBJECTS_DIR === '' && !process.env.CI, 'Needs .github/scripts/frontend-e2e.sh: the document pipeline enabled against the Storage and clamd doubles.');

const suffix = randomBytes(3).toString('hex');
const YEAR = Number(schoolToday().slice(0, 4));
const FAMILY_NAME = `E2E Descarga Familia ${suffix}`;
const ATHLETES = { main: `E2E-Descarga-Principal-${suffix}`, tampered: `E2E-Descarga-Alterada-${suffix}` } as const;
type AthleteKey = keyof typeof ATHLETES;
const JPEG_SIZE = { width: 1600, height: 1000 };
const FRONT = 'DNI – frente';
const BACK = 'DNI – dorso';
const PDF_READY = 'PDF listo. Para verlo hay que descargarlo: queda una copia en este dispositivo.';
const INTEGRITY_FAILED = 'El archivo guardado no superó la verificación de integridad, por eso no se muestra.';

interface StoredDocument {
  id: string;
  key: string;
  mime: string;
  bytes: number;
}
interface Uploaded {
  sent: Buffer;
  doc: StoredDocument;
}

let browserRef: Browser;
let adminContext: BrowserContext;
let admin: Page;
const adminConsole: string[] = [];
let family: SeededFamily;
let familyContext: BrowserContext;
const ids = {} as Record<AthleteKey, string>;
const dnis: string[] = [];
const docs = {} as { front: Uploaded; back: Uploaded; tampered: Uploaded };

const escapeRegex = (text: string) => text.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
/** Personal data of this file (support/leak.ts), the DNIs, and the base64 starts of a JPEG and a PDF (document bytes). */
const LEAK = () =>
  new RegExp(
    [
      leakPattern({ athleteName: ATHLETES.main, runnerNumber: '0816', category: 'Sub-11-Descarga', division: 'Descarga A', familyName: FAMILY_NAME }).source,
      ...Object.values(ATHLETES).map(escapeRegex),
      ...dnis,
      '/9j/', // base64 of a JPEG's first bytes
      'JVBERi', // base64 of "%PDF"
      'data:image',
    ].join('|'),
  );

const archivoPath = (athleteId: string, documentId: string) => `/api/admin/deportistas/${athleteId}/documentos/${documentId}/archivo`;
const isArchivo = (athleteId: string, documentId: string) => (response: Response) =>
  response.request().method() === 'GET' && new URL(response.url()).pathname === archivoPath(athleteId, documentId);
const documentsSection = (page: Page) => page.getByRole('region', { name: 'Documentos', exact: true });
const row = (page: Page, label: string) => documentsSection(page).getByRole('listitem').filter({ has: page.getByRole('heading', { name: label, exact: true }) });
const openButton = (page: Page, label: string) => documentsSection(page).getByRole('button', { name: `Abrir documento: ${label}`, exact: true });
const preview = (page: Page, label: string) => documentsSection(page).getByRole('region', { name: `Documento abierto: ${label}`, exact: true });
const noHorizontalOverflow = (page: Page) =>
  page.evaluate(() => {
    const section = [...document.querySelectorAll('section')].find((node) => node.getAttribute('aria-label') === 'Documentos');
    return document.documentElement.scrollWidth <= window.innerWidth && !!section && section.scrollWidth <= section.clientWidth;
  });

/** Read-only: the current document of one athlete and type, with the Storage key the backend recorded (never exposed by the API). */
function storedDocument(athleteId: string, tipo: string): StoredDocument {
  const out = dbQuery(
    "COPY (SELECT id, storage_key, mime_type, tamanio_bytes FROM gestion_patin.documento WHERE deportista_id = :'athlete' AND tipo = :'tipo' AND vigente) TO STDOUT;\n",
    { athlete: athleteId, tipo },
  );
  const lines = out.split('\n').filter((line) => line !== '');
  expect(lines, `exactly one current ${tipo}`).toHaveLength(1);
  const [id = '', key = '', mime = '', bytes = '0'] = lines[0]!.split('\t');
  return { id, key, mime, bytes: Number(bytes) };
}

/** Read-only: the audit trail of one document, oldest first (action, whether user and school are set, and the detail). */
function auditTrail(documentId: string): { action: string; withUser: boolean; detail: string }[] {
  const out = dbQuery(
    "COPY (SELECT accion, usuario_id IS NOT NULL AND escuela_id IS NOT NULL, detalle::text FROM gestion_patin.auditoria WHERE recurso_tipo = 'DOCUMENTO' AND recurso_id = :'doc' ORDER BY id) TO STDOUT;\n",
    { doc: documentId },
  );
  return out
    .split('\n')
    .filter((line) => line !== '')
    .map((line) => {
      const [action = '', withUser = 'f', detail = ''] = line.split('\t');
      return { action, withUser: withUser === 't', detail };
    });
}
/** The U16 actions of one document's trail (its upload is audited too, as DOCUMENTO_CARGADO, and is left out here). */
const DOWNLOAD_ACTIONS = new Set(['DOCUMENTO_DESCARGADO', 'DOCUMENTO_INTEGRIDAD_FALLIDA']);
const actions = (documentId: string) => auditTrail(documentId).map((entry) => entry.action).filter((action) => DOWNLOAD_ACTIONS.has(action));

/** The family's upload through the real API, shaped exactly like its browser sends it (U15): neutral name, CSRF, Idempotency-Key. */
async function upload(athleteId: string, tipo: string, file: { name: string; mimeType: string; buffer: Buffer }): Promise<Uploaded> {
  const response = await familyContext.request.post(`/api/familia/deportistas/${athleteId}/documentos`, {
    multipart: { tipo, archivo: file },
    headers: { ...(await csrfHeaders(familyContext.request)), 'Idempotency-Key': randomUUID() },
  });
  expect(response.status(), `upload ${tipo}`).toBe(201);
  const doc = storedDocument(athleteId, tipo);
  const stored = readFileSync(join(OBJECTS_DIR, doc.key));
  expect(stored.equals(file.buffer), 'the Storage double holds exactly the bytes the family sent').toBe(true);
  return { sent: file.buffer, doc };
}

async function openDetail(page: Page, key: AthleteKey): Promise<void> {
  await page.goto(`/admin/deportistas/${ids[key]}`);
  await expect(page.getByRole('heading', { level: 1 })).toContainText(ATHLETES[key]);
  await expect(documentsSection(page)).toBeVisible();
}

/** A structured route error: JSON with the expected code and none of the document's bytes. */
async function expectRouteError(response: APIResponse | Response, status: number, code: string | null, documentBytes: Buffer): Promise<void> {
  expect(response.status()).toBe(status);
  const body = await response.body();
  expect(body.length).toBeLessThan(2_000);
  expect(body.indexOf(documentBytes.subarray(0, 32)), 'no document bytes in an error').toBe(-1);
  expect(response.headers()['content-disposition']).toBeUndefined();
  if (code !== null) expect(((JSON.parse(body.toString('utf8')) as { codigo?: string }).codigo)).toBe(code);
}

/** Nothing personal and no document bytes in the address, the browser storage (Web Storage, IndexedDB, Cache Storage) or the console. */
async function expectNoLeaks(page: Page, consoleLines: string[]): Promise<void> {
  expect(page.url()).not.toMatch(LEAK());
  const stored = await page.evaluate(async () => ({
    web: JSON.stringify([{ ...localStorage }, { ...sessionStorage }]),
    indexedDb: (await indexedDB.databases()).map((db) => db.name ?? ''),
    caches: 'caches' in window ? await caches.keys() : [],
  }));
  expect(stored.web).not.toMatch(LEAK());
  expect(stored.indexedDb, 'no document is kept in IndexedDB').toEqual([]);
  expect(stored.caches, 'no document is kept in Cache Storage').toEqual([]);
  expect(consoleLines.join('\n')).not.toMatch(LEAK());
  expect(consoleLines.filter((line) => line.startsWith('pageerror:'))).toEqual([]);
}

test.beforeAll(async ({ browser }) => {
  if (OBJECTS_DIR === '') throw new Error('E2E_STORAGE_OBJECTS_DIR is not set: run .github/scripts/frontend-e2e.sh (document pipeline enabled against the doubles).');
  if (!hasDisposableDb()) throw new Error('E2E_DB_CONTAINER is not set: the stored objects and the audit trail are read from the disposable database.');
  browserRef = browser;
  adminContext = await browser.newContext({ baseURL: 'http://127.0.0.1:5173' });
  admin = await adminContext.newPage();
  admin.on('console', (message) => adminConsole.push(message.text()));
  admin.on('pageerror', (error) => adminConsole.push(`pageerror: ${error.message}`));
  await loginAdminUi(admin); // the only administrator login of this file (one TOTP window)
  const seed = new AdminSeed(adminContext.request);
  const anonymous = await browser.newContext({ baseURL: 'http://127.0.0.1:5173' });
  family = await seed.createFamilyWithUser(anonymous.request, FAMILY_NAME, {
    nombre: 'Rosa',
    apellido: 'Prueba',
    email: `descarga.${suffix}@example.test`,
    password: `Fam-${randomBytes(8).toString('hex')}`,
  });
  await anonymous.close();
  for (const key of Object.keys(ATHLETES) as AthleteKey[]) {
    const created = await seed.createAthlete({ nombre: ATHLETES[key], apellido: 'Sintetico' });
    ids[key] = created.id;
    dnis.push(created.dni);
  }
  await seed.link(family.familyId, Object.values(ids));
  await seed.addSportsRow(ids.main, (await seed.season(YEAR)).id, { categoria: 'Sub-11-Descarga', rama: 'Femenina', divisional: 'Descarga A', numeroCorredor: '0816' });

  // The family signs in through the real screen; its session then uploads through the real API.
  familyContext = await browserRef.newContext({ baseURL: 'http://127.0.0.1:5173' });
  const familyPage = await familyContext.newPage();
  await familyPage.goto('/login');
  await familyPage.getByLabel(/^Correo electrónico/).fill(family.email);
  await familyPage.getByLabel(/^Contraseña/).fill(family.password);
  await familyPage.getByRole('button', { name: 'Ingresar' }).click();
  await expect(familyPage).toHaveURL(/\/familia$/);

  // A JPEG rendered by the real browser from a canvas (no metadata: what the U15 normalization sends) and a synthetic PDF.
  const jpeg = await browserJpeg(admin, JPEG_SIZE);
  docs.front = await upload(ids.main, 'DNI_DEPORTISTA_FRENTE', { name: 'documento.jpg', mimeType: 'image/jpeg', buffer: jpeg });
  docs.back = await upload(ids.main, 'DNI_DEPORTISTA_DORSO', { name: 'documento.pdf', mimeType: 'application/pdf', buffer: syntheticPdf() });
  docs.tampered = await upload(ids.tampered, 'DNI_DEPORTISTA_FRENTE', { name: 'documento.jpg', mimeType: 'image/jpeg', buffer: await browserJpeg(admin, { width: 1200, height: 800 }) });
  expect(docs.front.doc).toMatchObject({ mime: 'image/jpeg', bytes: jpeg.length });
  expect(docs.back.doc).toMatchObject({ mime: 'application/pdf' });
});

test.afterAll(async () => {
  await familyContext?.close();
  await adminContext?.close();
});

test('mobile 360 px: «Documentos» lists both DNI sides as «Cargado», one «Abrir documento» each, without horizontal overflow', async () => {
  await admin.setViewportSize({ width: 360, height: 800 });
  await openDetail(admin, 'main');
  for (const label of [FRONT, BACK]) {
    await expect(row(admin, label).getByText('Estado: Cargado', { exact: true })).toBeVisible();
    await expect(row(admin, label).getByText(/^Fecha de carga: /)).toBeVisible();
    await expect(openButton(admin, label)).toBeEnabled();
  }
  await expect(documentsSection(admin).getByRole('listitem')).toHaveCount(2);
  await expect(documentsSection(admin).getByRole('img')).toHaveCount(0); // nothing is fetched until the administrator asks
  expect(await noHorizontalOverflow(admin)).toBe(true);
  expect(actions(docs.front.doc.id)).toEqual([]); // listing is not an access to the file
  expect(actions(docs.back.doc.id)).toEqual([]);
  await expectNoLeaks(admin, adminConsole);
});

test('mobile 360 px: «Abrir documento» shows the JPEG in-page from a blob URL; the bytes received equal the stored object and what the family sent', async () => {
  await openDetail(admin, 'main');
  const answer = admin.waitForResponse(isArchivo(ids.main, docs.front.doc.id));
  await openButton(admin, FRONT).click();
  const response = await answer;
  expect(response.status()).toBe(200);
  const headers = response.headers();
  expect(headers['content-type']).toBe('image/jpeg');
  expect(headers['content-disposition']).toBe('attachment; filename="dni-frente.jpg"'); // neutral: the type, never a person
  expect(headers['x-content-type-options']).toBe('nosniff');
  expect(headers['cache-control']).toBe('no-store');
  expect(headers['content-security-policy']).toContain('sandbox');
  const received = await response.body();
  const stored = readFileSync(join(OBJECTS_DIR, docs.front.doc.key));
  expect(sha256(received)).toBe(sha256(stored));
  expect(sha256(received)).toBe(sha256(docs.front.sent));

  const image = preview(admin, FRONT).getByRole('img', { name: `Documento: ${FRONT}`, exact: true });
  await expect(image).toBeVisible();
  const src = (await image.getAttribute('src')) ?? '';
  expect(src).toMatch(/^blob:http:\/\/127\.0\.0\.1:5173\//);
  await expect.poll(() => image.evaluate((node: HTMLImageElement) => [node.complete, node.naturalWidth, node.naturalHeight])).toEqual([true, JPEG_SIZE.width, JPEG_SIZE.height]);
  // What the page holds behind the blob URL is the very file the server sent.
  const inPage = await admin.evaluate(async (url) => {
    const digest = await crypto.subtle.digest('SHA-256', await (await fetch(url)).arrayBuffer());
    return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, '0')).join('');
  }, src);
  expect(inPage).toBe(sha256(stored));
  await expect(admin).toHaveURL(new RegExp(`/admin/deportistas/${ids.main}$`)); // the open document never reaches the address
  expect(await noHorizontalOverflow(admin)).toBe(true);
  await expectNoLeaks(admin, adminConsole);

  // «Cerrar» removes the image and revokes its URL: the bytes are no longer reachable from the page.
  await preview(admin, FRONT).getByRole('button', { name: 'Cerrar', exact: true }).click();
  await expect(preview(admin, FRONT)).toHaveCount(0);
  await expect(documentsSection(admin).getByRole('img')).toHaveCount(0);
  expect(await admin.evaluate((url) => fetch(url).then(() => 'readable', () => 'revoked'), src)).toBe('revoked');
  await expect(openButton(admin, FRONT)).toBeFocused();
});

test('PDF: «Abrir documento» says «PDF listo» and renders nothing; «Descargar PDF» downloads a neutral file name with the uploaded bytes', async () => {
  await admin.setViewportSize({ width: 1280, height: 720 });
  await openDetail(admin, 'main');
  const answer = admin.waitForResponse(isArchivo(ids.main, docs.back.doc.id));
  await openButton(admin, BACK).click();
  const response = await answer;
  expect(response.status()).toBe(200);
  expect(response.headers()['content-type']).toBe('application/pdf');
  expect(response.headers()['content-disposition']).toBe('attachment; filename="dni-dorso.pdf"');
  expect(response.headers()['x-content-type-options']).toBe('nosniff');
  expect(response.headers()['cache-control']).toBe('no-store');
  expect(sha256(await response.body())).toBe(sha256(docs.back.sent));

  await expect(preview(admin, BACK).getByText(PDF_READY, { exact: true })).toBeVisible();
  await expect(documentsSection(admin).locator('iframe, embed, object, img')).toHaveCount(0); // never rendered in the page
  const download = admin.waitForEvent('download');
  await preview(admin, BACK).getByRole('button', { name: 'Descargar PDF', exact: true }).click();
  const file = await download;
  expect(file.suggestedFilename()).toBe('dni-dorso.pdf');
  expect(file.url()).toMatch(/^blob:/); // from memory: the download is not a second request to the server
  const path = await file.path();
  expect(sha256(readFileSync(path))).toBe(sha256(docs.back.sent));
  expect(sha256(readFileSync(path))).toBe(sha256(readFileSync(join(OBJECTS_DIR, docs.back.doc.key))));
  await file.delete();
  await preview(admin, BACK).getByRole('button', { name: 'Cerrar', exact: true }).click();
  await expect(preview(admin, BACK)).toHaveCount(0);
  await expectNoLeaks(admin, adminConsole);
});

test('integrity: a same-size object with other bytes answers 502 INTEGRIDAD_FALLIDA and the screen shows nothing of it; a shorter object is 502 and a larger one 503', async () => {
  const objectPath = join(OBJECTS_DIR, docs.tampered.doc.key);
  const original = readFileSync(objectPath);
  const sameSize = Buffer.from(original);
  for (let i = sameSize.length - 64; i < sameSize.length - 2; i++) sameSize[i] = sameSize[i]! ^ 0xff; // same length, other bytes
  expect(sameSize.length).toBe(original.length);
  expect(sameSize.equals(original)).toBe(false);
  writeFileSync(objectPath, sameSize);
  try {
    const downloads: string[] = [];
    admin.on('download', (event) => downloads.push(event.suggestedFilename()));
    await openDetail(admin, 'tampered');
    const answer = admin.waitForResponse(isArchivo(ids.tampered, docs.tampered.doc.id));
    await openButton(admin, FRONT).click();
    const response = await answer;
    await expectRouteError(response, 502, 'INTEGRIDAD_FALLIDA', sameSize);
    await expect(preview(admin, FRONT).getByRole('alert').filter({ hasText: INTEGRITY_FAILED })).toBeVisible();
    await expect(documentsSection(admin).getByRole('img')).toHaveCount(0);
    await expect(documentsSection(admin).getByRole('button', { name: 'Descargar PDF' })).toHaveCount(0);
    expect(downloads).toEqual([]);
    expect(actions(docs.tampered.doc.id)).toEqual(['DOCUMENTO_INTEGRIDAD_FALLIDA']);

    // A shorter object: the size check refuses it before the hash (502, audited again). A LARGER one is refused by the bounded
    // read itself (it declares more than the registered size): 503 DESCARGA_NO_DISPONIBLE, and nothing is audited.
    const csrf = await csrfHeaders(adminContext.request);
    writeFileSync(objectPath, original.subarray(0, original.length - 10));
    await expectRouteError(await adminContext.request.get(archivoPath(ids.tampered, docs.tampered.doc.id), { headers: csrf }), 502, 'INTEGRIDAD_FALLIDA', original);
    writeFileSync(objectPath, Buffer.concat([original, Buffer.from('extra-bytes')]));
    await expectRouteError(await adminContext.request.get(archivoPath(ids.tampered, docs.tampered.doc.id), { headers: csrf }), 503, 'DESCARGA_NO_DISPONIBLE', original);
    expect(actions(docs.tampered.doc.id)).toEqual(['DOCUMENTO_INTEGRIDAD_FALLIDA', 'DOCUMENTO_INTEGRIDAD_FALLIDA']);
    admin.removeAllListeners('download');
  } finally {
    writeFileSync(objectPath, original); // the inspection directory is left as the backend wrote it
  }
  await expectNoLeaks(admin, adminConsole);
});

test('security: without the CSRF header 403 CSRF_INVALIDO; a family session 403; anonymous 401; another athlete in the path 404 DOCUMENTO_NO_ENCONTRADO; none is audited', async () => {
  const path = archivoPath(ids.main, docs.front.doc.id);
  const before = auditTrail(docs.front.doc.id).length;
  const bytes = docs.front.sent;
  const adminCsrf = await csrfHeaders(adminContext.request);

  await expectRouteError(await adminContext.request.get(path), 403, 'CSRF_INVALIDO', bytes);
  await expectRouteError(await adminContext.request.get(path, { headers: { 'X-XSRF-TOKEN': 'not-the-token' } }), 403, 'CSRF_INVALIDO', bytes);
  await expectRouteError(await adminContext.request.get(path, { headers: { 'X-XSRF-TOKEN': '' } }), 403, 'CSRF_INVALIDO', bytes);
  // The query-string parameter of a form does not stand in for the header on this route.
  await expectRouteError(await adminContext.request.get(`${path}?_csrf=${encodeURIComponent(adminCsrf['X-XSRF-TOKEN']!)}`), 403, 'CSRF_INVALIDO', bytes);

  await expectRouteError(await familyContext.request.get(path, { headers: await csrfHeaders(familyContext.request) }), 403, 'ACCESO_DENEGADO', bytes);

  const anonymousContext = await browserRef.newContext({ baseURL: 'http://127.0.0.1:5173' });
  const anonymous = anonymousContext.request;
  await expectRouteError(await anonymous.get(path, { headers: await csrfHeaders(anonymous) }), 401, null, bytes);
  await anonymousContext.close();

  // Another athlete of the same school, or an unknown document: the same uniform 404, without reading Storage.
  await expectRouteError(await adminContext.request.get(archivoPath(ids.tampered, docs.front.doc.id), { headers: adminCsrf }), 404, 'DOCUMENTO_NO_ENCONTRADO', bytes);
  await expectRouteError(await adminContext.request.get(archivoPath(ids.main, docs.tampered.doc.id), { headers: adminCsrf }), 404, 'DOCUMENTO_NO_ENCONTRADO', bytes);
  await expectRouteError(await adminContext.request.get(archivoPath(ids.main, randomUUID()), { headers: adminCsrf }), 404, 'DOCUMENTO_NO_ENCONTRADO', bytes);

  expect(auditTrail(docs.front.doc.id)).toHaveLength(before);
  expect(actions(docs.tampered.doc.id)).toEqual(['DOCUMENTO_INTEGRIDAD_FALLIDA', 'DOCUMENTO_INTEGRIDAD_FALLIDA']);
});

test('audit: one DOCUMENTO_DESCARGADO per delivered open, the integrity failures audited apart, and no SHA, key or bytes in any detail', () => {
  const front = auditTrail(docs.front.doc.id);
  const back = auditTrail(docs.back.doc.id);
  const tampered = auditTrail(docs.tampered.doc.id);
  // The whole trail, oldest first: the family's upload, then only what U16 did with each document.
  expect(front.map((entry) => entry.action)).toEqual(['DOCUMENTO_CARGADO', 'DOCUMENTO_DESCARGADO']);
  expect(back.map((entry) => entry.action)).toEqual(['DOCUMENTO_CARGADO', 'DOCUMENTO_DESCARGADO']);
  expect(tampered.map((entry) => entry.action)).toEqual(['DOCUMENTO_CARGADO', 'DOCUMENTO_INTEGRIDAD_FALLIDA', 'DOCUMENTO_INTEGRIDAD_FALLIDA']);
  expect(tampered.map((entry) => entry.action)).not.toContain('DOCUMENTO_DESCARGADO');
  for (const [entry, uploaded] of [...front.map((e) => [e, docs.front] as const), ...back.map((e) => [e, docs.back] as const), ...tampered.map((e) => [e, docs.tampered] as const)]) {
    expect(entry.withUser, 'the administrator and the school are recorded').toBe(true);
    expect(entry.detail).not.toContain(uploaded.doc.key);
    expect(entry.detail).not.toContain(sha256(uploaded.sent));
    expect(entry.detail).not.toMatch(LEAK());
  }
});
