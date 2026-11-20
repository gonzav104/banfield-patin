import { randomBytes } from 'node:crypto';
import { readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { expect, test, type Browser, type BrowserContext, type Page } from '@playwright/test';
import { dbQuery, hasDisposableDb } from './support/db';
import { browserJpeg, sha256, syntheticPdf } from './support/documents';
import { eicarBytes } from './support/fakes/eicar';
import { addPrivacyHazards, inspectJpeg } from './support/jpeg';
import { leakPattern } from './support/leak';
import { AdminSeed, loginAdminUi, schoolToday, type SeededFamily } from './support/seed';

/**
 * REAL upload of the athlete's DNI from the family sheet (FE-06/U15): browser (Canvas normalization) -> Vite proxy -> Spring
 * backend with the upload ENABLED -> PostgreSQL, against two external TEST DOUBLES started by frontend-e2e.sh: a Supabase
 * Storage subset (support/fakes/storage-fake.ts) and a clamd INSTREAM double (support/fakes/clamd-fake.ts). The backend
 * pipeline (validation, content inspection, scan, idempotency, publication) is the real one and is not touched. The doubles
 * do NOT replace the opt-in tests against a real Supabase and a real ClamAV (docs/DOCUMENTOS.md).
 *
 * The photo is rendered by the real browser from a canvas and then given what a phone photo carries: Exif with GPS and
 * bytes after EOI (support/jpeg.ts). The object the backend stored is read back from the Storage double's inspection
 * directory (E2E_STORAGE_OBJECTS_DIR), located through the disposable database (read-only queries).
 * The test that says SIMULATED in its title intercepts the answer through the Chrome DevTools Protocol: the backend really
 * processes the upload the browser sent, only its answer is lost.
 *
 * One athlete per test, so no case sees another's documents.
 */
test.describe.configure({ mode: 'serial' });

const OBJECTS_DIR = process.env.E2E_STORAGE_OBJECTS_DIR ?? '';
// Outside frontend-e2e.sh (no doubles, upload disabled) this file skips EXPLICITLY; in CI a missing setup is a failure.
test.skip(OBJECTS_DIR === '' && !process.env.CI, 'Needs .github/scripts/frontend-e2e.sh: the upload enabled against the Storage and clamd doubles.');

const suffix = randomBytes(3).toString('hex');
const YEAR = Number(schoolToday().slice(0, 4));
const FAMILY_NAME = `E2E Docs Familia ${suffix}`;
const ATHLETES = { main: `E2E-Docs-Principal-${suffix}`, lost: `E2E-Docs-Perdida-${suffix}`, eicar: `E2E-Docs-Eicar-${suffix}` } as const;
type AthleteKey = keyof typeof ATHLETES;
/** The original file names a phone gives: they carry personal data and must never reach the server, the address or storage. */
const PHOTO_NAME = `IMG_20261120_DNI_frente_${ATHLETES.main}.jpg`;
const PDF_NAME = `Escaneo DNI dorso ${ATHLETES.main}.pdf`;
const SUPPORT_NAME = `Comprobante ${ATHLETES.eicar}.pdf`;
const PHOTO_SIZE = { width: 3200, height: 2000 };
const MAX_LONG_SIDE = 2400;
const UNCONFIRMED = 'No pudimos confirmar si el documento se guardó.';
const DANGEROUS = 'El archivo no pasó el control de seguridad. Elegí otro archivo.';
const SENT = 'Documento enviado. El club lo va a revisar.';

let browserRef: Browser;
let adminContext: BrowserContext;
let family: SeededFamily;
const ids = {} as Record<AthleteKey, string>;
const dnis: string[] = [];
let photo: Buffer;

const escapeRegex = (text: string) => text.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
/** Personal data of this file: the athlete's sports data and family (support/leak.ts), DNIs and the original file names. */
const LEAK = () =>
  new RegExp(
    [
      leakPattern({ athleteName: ATHLETES.main, runnerNumber: '0815', category: 'Sub-11-Docs', division: 'Docs A', familyName: FAMILY_NAME }).source,
      ...Object.values(ATHLETES).map(escapeRegex),
      ...dnis,
      ...[PHOTO_NAME, PDF_NAME, SUPPORT_NAME].map(escapeRegex),
      'IMG_20261120',
      'Escaneo',
    ].join('|'),
  );

const objectFiles = () => readdirSync(OBJECTS_DIR).filter((name) => !name.endsWith('.json') && !name.endsWith('.tmp'));
const noHorizontalOverflow = (page: Page) => page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth);
const documentsRegion = (page: Page) => page.getByRole('region', { name: 'Documentos del deportista' });
const slot = (page: Page, side: 'frente' | 'dorso') => documentsRegion(page).getByRole('article', { name: `DNI – ${side}` });
const badge = (page: Page, side: 'frente' | 'dorso', label: string) => slot(page, side).getByText(`Estado: ${label}`, { exact: true });
const isUpload = (athleteId: string) => (response: { url(): string; request(): { method(): string } }) =>
  response.request().method() === 'POST' && new URL(response.url()).pathname === `/api/familia/deportistas/${athleteId}/documentos`;

/** The documents of one athlete as the backend reports them to the family (the API the screen uses). */
async function backendDocuments(page: Page, athleteId: string): Promise<{ tipo: string; estado: string }[]> {
  const response = await page.request.get(`/api/familia/deportistas/${athleteId}/documentos?page=0&size=100`);
  expect(response.status()).toBe(200);
  return ((await response.json()) as { contenido: { tipo: string; estado: string }[] }).contenido;
}

/** Read-only: where the backend stored one document, and the name and type it recorded (never exposed by the API). */
function storedDocument(athleteId: string, tipo: string): { key: string; mime: string; originalName: string; bytes: number }[] {
  const out = dbQuery(
    "COPY (SELECT storage_key, mime_type, nombre_original, tamanio_bytes FROM gestion_patin.documento WHERE deportista_id = :'athlete' AND tipo = :'tipo') TO STDOUT;\n",
    { athlete: athleteId, tipo },
  );
  return out
    .split('\n')
    .filter((line) => line !== '')
    .map((line) => {
      const [key = '', mime = '', originalName = '', bytes = '0'] = line.split('\t');
      return { key, mime, originalName, bytes: Number(bytes) };
    });
}

/** Read-only: how many upload operations the backend registered for one athlete and side. */
function operationCount(athleteId: string, tipo: string): number {
  const out = dbQuery("COPY (SELECT count(*) FROM gestion_patin.operacion_carga_documental WHERE deportista_id = :'athlete' AND tipo = :'tipo') TO STDOUT;\n", {
    athlete: athleteId,
    tipo,
  });
  return Number(out.trim());
}

/**
 * What the page handed to the network for each upload, recorded inside the page (test-only instrumentation of
 * XMLHttpRequest, which axios uses): the Idempotency-Key header and the `archivo` part's file name, type, size and SHA-256.
 * Chromium does not expose blob-backed multipart bodies to Playwright (`postDataBuffer()` is null), so this is how the E2E
 * sees the exact bytes the browser sent. It changes nothing in what is sent.
 */
const RECORD_UPLOADS = () => {
  const sent: { key: string | null; name: string; type: string; size: number; sha256: string | null }[] = [];
  (window as unknown as { __e2eUploads: typeof sent }).__e2eUploads = sent;
  // The originals are re-invoked with `.call(this, ...)` below, so keeping them unbound is intended.
  // eslint-disable-next-line @typescript-eslint/unbound-method
  const setHeader = XMLHttpRequest.prototype.setRequestHeader;
  // eslint-disable-next-line @typescript-eslint/unbound-method
  const send = XMLHttpRequest.prototype.send;
  XMLHttpRequest.prototype.setRequestHeader = function (name: string, value: string) {
    if (name.toLowerCase() === 'idempotency-key') (this as unknown as { e2eKey: string }).e2eKey = value;
    return setHeader.call(this, name, value);
  };
  XMLHttpRequest.prototype.send = function (body?: Document | XMLHttpRequestBodyInit | null) {
    const file = body instanceof FormData ? body.get('archivo') : null;
    if (file instanceof File) {
      const entry = { key: (this as unknown as { e2eKey?: string }).e2eKey ?? null, name: file.name, type: file.type, size: file.size, sha256: null as string | null };
      sent.push(entry);
      void file
        .arrayBuffer()
        .then((bytes) => crypto.subtle.digest('SHA-256', bytes))
        .then((digest) => {
          entry.sha256 = [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, '0')).join('');
        });
    }
    return send.call(this, body);
  };
};
type SentUpload = { key: string | null; name: string; type: string; size: number; sha256: string | null };
async function sentUploads(page: Page, count: number): Promise<SentUpload[]> {
  const read = () => page.evaluate(() => (window as unknown as { __e2eUploads?: SentUpload[] }).__e2eUploads ?? []);
  await expect.poll(async () => (await read()).filter((u) => u.sha256 !== null).length).toBe(count);
  return read();
}

async function familyPage(viewport?: { width: number; height: number }): Promise<{ context: BrowserContext; page: Page; consoleLines: string[] }> {
  const context = await browserRef.newContext({ baseURL: 'http://127.0.0.1:5173', ...(viewport ? { viewport } : {}) });
  await context.addInitScript(RECORD_UPLOADS);
  const page = await context.newPage();
  const consoleLines: string[] = [];
  page.on('console', (message) => consoleLines.push(message.text()));
  page.on('pageerror', (error) => consoleLines.push(`pageerror: ${error.message}`));
  await page.goto('/login');
  await page.getByLabel(/^Correo electrónico/).fill(family.email);
  await page.getByLabel(/^Contraseña/).fill(family.password);
  await page.getByRole('button', { name: 'Ingresar' }).click();
  await expect(page).toHaveURL(/\/familia$/);
  return { context, page, consoleLines };
}

async function openSheet(page: Page, key: AthleteKey): Promise<void> {
  await page.goto(`/familia/deportistas/${ids[key]}`);
  await expect(page.getByRole('heading', { level: 1 })).toContainText(ATHLETES[key]);
  await expect(documentsRegion(page)).toBeVisible();
}

/** Nothing personal in the address, the browser storage (Web Storage, IndexedDB, Cache Storage) or the console. */
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
  if (OBJECTS_DIR === '') throw new Error('E2E_STORAGE_OBJECTS_DIR is not set: run .github/scripts/frontend-e2e.sh (upload enabled against the doubles).');
  if (!hasDisposableDb()) throw new Error('E2E_DB_CONTAINER is not set: the stored object is located through the disposable database.');
  browserRef = browser;
  adminContext = await browser.newContext({ baseURL: 'http://127.0.0.1:5173' });
  const adminPage = await adminContext.newPage();
  await loginAdminUi(adminPage);
  const seed = new AdminSeed(adminContext.request);
  const anonymous = await browser.newContext({ baseURL: 'http://127.0.0.1:5173' });
  family = await seed.createFamilyWithUser(anonymous.request, FAMILY_NAME, {
    nombre: 'Marta',
    apellido: 'Prueba',
    email: `docs.${suffix}@example.test`,
    password: `Fam-${randomBytes(8).toString('hex')}`,
  });
  await anonymous.close();
  for (const key of Object.keys(ATHLETES) as AthleteKey[]) {
    const created = await seed.createAthlete({ nombre: ATHLETES[key], apellido: 'Sintetico' });
    ids[key] = created.id;
    dnis.push(created.dni);
  }
  await seed.link(family.familyId, Object.values(ids));
  // Sports data of the main athlete (not activated: no other spec's season changes), so the leak pattern has real values.
  await seed.addSportsRow(ids.main, (await seed.season(YEAR)).id, { categoria: 'Sub-11-Docs', rama: 'Femenina', divisional: 'Docs A', numeroCorredor: '0815' });

  // The fixture: rendered by the real browser, then given GPS Exif and trailing bytes. Proven here, not assumed.
  photo = Buffer.from(addPrivacyHazards(new Uint8Array(await browserJpeg(adminPage, PHOTO_SIZE))));
  expect(inspectJpeg(photo)).toMatchObject({ isJpeg: true, hasExif: true, hasGps: true, ...PHOTO_SIZE });
  expect(inspectJpeg(photo).trailingBytes).toBeGreaterThan(100);
});

test.afterAll(async () => {
  await adminContext?.close();
});

test('mobile 360 px: both sides start as «Falta»; a GPS photo and a PDF are uploaded and the stored photo carries no metadata', async () => {
  const { context, page, consoleLines } = await familyPage({ width: 360, height: 800 });
  const availability = await page.request.get('/api/familia/documentos/disponibilidad');
  expect(await availability.json()).toEqual({ habilitada: true, maxBytes: 5_000_000 }); // enabled ONLY by the E2E script
  await openSheet(page, 'main');
  await expect(badge(page, 'frente', 'Falta')).toBeVisible();
  await expect(badge(page, 'dorso', 'Falta')).toBeVisible();
  expect(await noHorizontalOverflow(page)).toBe(true);
  const objectsBefore = objectFiles();

  // Front: the "camera" input receives the phone photo (GPS Exif + bytes after EOI, 3200x2000).
  await slot(page, 'frente').getByLabel('Sacar foto del DNI – frente', { exact: true }).setInputFiles({ name: PHOTO_NAME, mimeType: 'image/jpeg', buffer: photo });
  await expect(slot(page, 'frente').getByRole('img', { name: 'Vista previa del DNI – frente' })).toBeVisible();
  expect(await noHorizontalOverflow(page)).toBe(true);
  const front = page.waitForResponse(isUpload(ids.main));
  await slot(page, 'frente').getByRole('button', { name: 'Enviar' }).click();
  expect((await front).status()).toBe(201);
  await expect(slot(page, 'frente').getByText(SENT)).toBeVisible();
  await expect(badge(page, 'frente', 'En revisión')).toBeVisible();

  // What the backend stored: one object, a JPEG with no Exif/GPS, nothing after EOI, long side scaled to 2400 px.
  const [frontDoc, ...extraFront] = storedDocument(ids.main, 'DNI_DEPORTISTA_FRENTE');
  expect(extraFront).toEqual([]);
  expect(frontDoc).toMatchObject({ mime: 'image/jpeg', originalName: 'documento' }); // the backend records a fixed name, never the client's
  const storedPhoto = readFileSync(join(OBJECTS_DIR, frontDoc!.key));
  expect(storedPhoto.length).toBe(frontDoc!.bytes);
  const [sentPhoto] = await sentUploads(page, 1);
  expect(sentPhoto).toMatchObject({ name: 'documento.jpg', type: 'image/jpeg', size: storedPhoto.length }); // a neutral name, never PHOTO_NAME
  expect(sentPhoto!.sha256).toBe(sha256(storedPhoto)); // what the browser normalized is exactly what was stored
  expect(sentPhoto!.sha256).not.toBe(sha256(photo));
  const report = inspectJpeg(new Uint8Array(storedPhoto));
  expect(report).toMatchObject({ isJpeg: true, hasExif: false, hasGps: false, trailingBytes: 0 });
  expect(Math.max(report.width!, report.height!)).toBe(MAX_LONG_SIDE);
  expect([report.width, report.height]).toEqual([2400, 1500]); // the aspect ratio is kept
  expect(JSON.parse(readFileSync(join(OBJECTS_DIR, `${frontDoc!.key}.meta.json`), 'utf8'))).toMatchObject({ contentType: 'image/jpeg' });

  // Back: a PDF is sent unchanged (byte for byte), with a neutral name.
  const pdf = syntheticPdf();
  await slot(page, 'dorso').getByLabel('Elegir archivo para el DNI – dorso', { exact: true }).setInputFiles({ name: PDF_NAME, mimeType: 'application/pdf', buffer: pdf });
  await expect(slot(page, 'dorso').getByText(/^PDF listo para enviar \(\d+ KB\)\.$/)).toBeVisible();
  const back = page.waitForResponse(isUpload(ids.main));
  await slot(page, 'dorso').getByRole('button', { name: 'Enviar' }).click();
  expect((await back).status()).toBe(201);
  await expect(badge(page, 'dorso', 'En revisión')).toBeVisible();
  const [backDoc, ...extraBack] = storedDocument(ids.main, 'DNI_DEPORTISTA_DORSO');
  expect(extraBack).toEqual([]);
  expect(backDoc).toMatchObject({ mime: 'application/pdf', originalName: 'documento' });
  expect(readFileSync(join(OBJECTS_DIR, backDoc!.key)).equals(pdf)).toBe(true);
  expect((await sentUploads(page, 2))[1]).toMatchObject({ name: 'documento.pdf', type: 'application/pdf', size: pdf.length, sha256: sha256(pdf) });
  expect(objectFiles().filter((name) => !objectsBefore.includes(name)).sort()).toEqual([frontDoc!.key, backDoc!.key].sort());

  // After a reload the states come from the backend, not from the screen's memory.
  const list = page.waitForResponse((response) => response.request().method() === 'GET' && new URL(response.url()).pathname === `/api/familia/deportistas/${ids.main}/documentos`);
  await page.reload();
  expect((await list).status()).toBe(200);
  await expect(badge(page, 'frente', 'En revisión')).toBeVisible();
  await expect(badge(page, 'dorso', 'En revisión')).toBeVisible();
  await expect(slot(page, 'frente').getByText(SENT)).toHaveCount(0);
  expect((await backendDocuments(page, ids.main)).map((d) => [d.tipo, d.estado]).sort()).toEqual([
    ['DNI_DEPORTISTA_DORSO', 'CARGADO'],
    ['DNI_DEPORTISTA_FRENTE', 'CARGADO'],
  ]);
  expect(await noHorizontalOverflow(page)).toBe(true);

  await expectNoLeaks(page, consoleLines);
  await context.close();
});

test('SIMULATED lost response (CDP response interception): the backend stores the photo, the browser never hears it; «Reintentar» resends the same key and bytes and stores it ONCE', async () => {
  const { context, page, consoleLines } = await familyPage();
  await openSheet(page, 'lost');
  const objectsBefore = objectFiles();
  // The browser itself sends the upload to the real backend (the body is never rebuilt by the test). The interception
  // pauses only the ANSWER of this POST: the first one is cut as a connection reset, so the page never learns the result.
  const statuses: number[] = [];
  const cdp = await context.newCDPSession(page);
  cdp.on('Fetch.requestPaused', (event) => {
    void (async () => {
      if (event.request.method !== 'POST' || event.responseStatusCode === undefined) {
        await cdp.send('Fetch.continueRequest', { requestId: event.requestId });
        return;
      }
      statuses.push(event.responseStatusCode);
      if (statuses.length === 1) await cdp.send('Fetch.failRequest', { requestId: event.requestId, errorReason: 'ConnectionReset' });
      else await cdp.send('Fetch.continueResponse', { requestId: event.requestId });
    })();
  });
  await cdp.send('Fetch.enable', { patterns: [{ urlPattern: `*/api/familia/deportistas/${ids.lost}/documentos`, requestStage: 'Response' }] });

  await slot(page, 'frente').getByLabel('Elegir archivo para el DNI – frente', { exact: true }).setInputFiles({ name: PHOTO_NAME, mimeType: 'image/jpeg', buffer: photo });
  await expect(slot(page, 'frente').getByRole('img', { name: 'Vista previa del DNI – frente' })).toBeVisible();
  await slot(page, 'frente').getByRole('button', { name: 'Enviar' }).click();
  await expect(slot(page, 'frente').getByRole('alert').filter({ hasText: UNCONFIRMED })).toBeVisible();
  await expect(badge(page, 'frente', 'Falta')).toBeVisible(); // the screen does not claim what it could not confirm
  expect(statuses).toEqual([201]); // ...although the backend did store it

  await slot(page, 'frente').getByRole('button', { name: 'Reintentar' }).click();
  await expect(slot(page, 'frente').getByText(SENT)).toBeVisible();
  await expect(badge(page, 'frente', 'En revisión')).toBeVisible();
  await cdp.detach();

  // The retry is the SAME operation: same Idempotency-Key and the same normalized bytes, so the backend answers the
  // document it already stored (200, not 201; a different key or different bytes would have been a new upload or a 409).
  expect(statuses).toEqual([201, 200]);
  const [first, second] = await sentUploads(page, 2);
  expect(first!.key).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/);
  expect(second!.key).toBe(first!.key);
  expect(second!.sha256).toBe(first!.sha256);
  expect(second!.size).toBe(first!.size);

  // Exactly ONE document for that side (backend list), ONE operation and ONE object in the Storage double.
  expect((await backendDocuments(page, ids.lost)).map((d) => [d.tipo, d.estado])).toEqual([['DNI_DEPORTISTA_FRENTE', 'CARGADO']]);
  expect(operationCount(ids.lost, 'DNI_DEPORTISTA_FRENTE')).toBe(1);
  const docs = storedDocument(ids.lost, 'DNI_DEPORTISTA_FRENTE');
  expect(docs).toHaveLength(1);
  expect(objectFiles().filter((name) => !objectsBefore.includes(name))).toEqual([docs[0]!.key]);
  expect(sha256(readFileSync(join(OBJECTS_DIR, docs[0]!.key)))).toBe(first!.sha256); // the bytes sent are the bytes stored

  await expectNoLeaks(page, consoleLines);
  await context.close();
});

test('a PDF carrying the EICAR test string is rejected by the scan with 422: the family is told to choose another file and nothing is stored', async () => {
  const { context, page, consoleLines } = await familyPage();
  await openSheet(page, 'eicar');
  const objectsBefore = objectFiles();

  await slot(page, 'dorso').getByLabel('Elegir archivo para el DNI – dorso', { exact: true }).setInputFiles({ name: SUPPORT_NAME, mimeType: 'application/pdf', buffer: syntheticPdf(eicarBytes()) });
  await expect(slot(page, 'dorso').getByText(/^PDF listo para enviar/)).toBeVisible();
  const upload = page.waitForResponse(isUpload(ids.eicar));
  await slot(page, 'dorso').getByRole('button', { name: 'Enviar' }).click();
  const response = await upload;
  expect(response.status()).toBe(422);
  expect(((await response.json()) as { codigo?: string }).codigo).toBe('ARCHIVO_PELIGROSO'); // the scan, not the PDF inspection (ARCHIVO_INVALIDO)

  await expect(slot(page, 'dorso').getByRole('alert').filter({ hasText: DANGEROUS })).toBeVisible();
  await expect(slot(page, 'dorso').getByRole('button', { name: 'Reintentar' })).toHaveCount(0); // the same file is never retried
  await expect(slot(page, 'dorso').getByLabel('Elegir archivo para el DNI – dorso', { exact: true })).toBeAttached(); // another file can be chosen
  await expect(badge(page, 'dorso', 'Falta')).toBeVisible();
  expect(await backendDocuments(page, ids.eicar)).toEqual([]);
  expect(storedDocument(ids.eicar, 'DNI_DEPORTISTA_DORSO')).toEqual([]);
  expect(objectFiles().filter((name) => !objectsBefore.includes(name))).toEqual([]);
  expect((await sentUploads(page, 1))[0]).toMatchObject({ name: 'documento.pdf', type: 'application/pdf' });

  await expectNoLeaks(page, consoleLines);
  await context.close();
});
