import { act, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { QueryClient } from '@tanstack/react-query';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { FakeAdminAthlete, FakeBackend, FakeDocument } from '@/test/fakeBackend';
import { createBackend, renderApp } from '@/test/render';
import { pdfFileName } from './api';

// MOCK backend (src/test/fakeBackend.ts mirrors GET /api/admin/deportistas/{id}/documentos and .../{documentId}/archivo, U16).
let backend: FakeBackend;
let athlete: FakeAdminAthlete;
let created: string[];
let revoked: string[];
let clicks: { href: string; download: string }[];
const originalCreate = (URL as { createObjectURL?: unknown }).createObjectURL;
const originalRevoke = (URL as { revokeObjectURL?: unknown }).revokeObjectURL;

const PNG = () => new Blob([new Uint8Array([0x89, 0x50, 0x4e, 0x47])], { type: 'image/png' });
const PDF = () => new Blob(['%PDF-1.7'], { type: 'application/pdf' });

beforeEach(() => {
  localStorage.clear();
  sessionStorage.clear();
  backend = createBackend();
  athlete = backend.addAdminAthlete({ nombre: 'Lucía', apellido: 'Sintética', dni: '30111322' });
  created = [];
  revoked = [];
  clicks = [];
  // jsdom implements neither: stubbed for the tests only.
  Object.defineProperty(URL, 'createObjectURL', {
    configurable: true,
    writable: true,
    value: vi.fn(() => {
      const url = `blob:http://app.test/${created.length + 1}`;
      created.push(url);
      return url;
    }),
  });
  Object.defineProperty(URL, 'revokeObjectURL', { configurable: true, writable: true, value: vi.fn((url: string) => revoked.push(url)) });
  vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (this: HTMLAnchorElement) {
    clicks.push({ href: this.getAttribute('href') ?? '', download: this.download });
  });
});

afterEach(() => {
  Object.defineProperty(URL, 'createObjectURL', { configurable: true, writable: true, value: originalCreate });
  Object.defineProperty(URL, 'revokeObjectURL', { configurable: true, writable: true, value: originalRevoke });
});

const fileUrl = (document: FakeDocument) => `/admin/deportistas/${athlete.id}/documentos/${document.id}/archivo`;
const fileRequests = () => backend.requests.filter((r) => /\/archivo$/.test(r.url));
const section = () => screen.getByRole('region', { name: 'Documentos' });
const openButton = (label: string) => within(section()).getByRole('button', { name: `Abrir documento: ${label}` });

function open(): { queryClient: QueryClient } {
  backend.signIn('marcos@example.com');
  return renderApp(`/admin/deportistas/${athlete.id}`);
}

async function openDetail() {
  const result = open();
  await screen.findByRole('heading', { level: 1, name: 'Lucía Sintética' });
  return result;
}

describe('athlete documents: list (mock backend)', () => {
  it('lists every current document with its readable type, its state in writing, its upload date and the reason only when observed', async () => {
    backend.seedDocument(athlete.id, { tipo: 'DNI_DEPORTISTA_FRENTE', estado: 'APROBADO', creadoEn: '2026-03-10T14:00:00Z' });
    backend.seedDocument(athlete.id, { tipo: 'DNI_TUTOR_DORSO', estado: 'OBSERVADO', motivoObservacion: 'La foto está borrosa.', creadoEn: '2026-03-11T02:30:00Z' });
    backend.seedDocument(athlete.id, { tipo: 'APTO_FISICO', estado: 'VENCIDO' });
    backend.seedDocument(athlete.id, { tipo: 'CERTIFICADO_WADA', estado: 'PENDIENTE' });
    await openDetail();

    const rows = await within(section()).findAllByRole('listitem');
    expect(rows.map((row) => within(row).getByRole('heading').textContent)).toEqual(['DNI – frente', 'DNI del tutor – dorso', 'Apto físico', 'Certificado WADA']);
    expect(rows[0]).toHaveTextContent('Estado: Aprobado');
    expect(rows[0]).toHaveTextContent('Fecha de carga: 10 mar 2026');
    // 02:30 UTC is still the 10th in Buenos Aires.
    expect(rows[1]).toHaveTextContent('Fecha de carga: 10 mar 2026');
    expect(rows[1]).toHaveTextContent('Estado: Observado');
    expect(rows[1]).toHaveTextContent('Motivo de la observación: La foto está borrosa.');
    expect(rows[2]).toHaveTextContent('Estado: Vencido');
    expect(rows[3]).toHaveTextContent('Estado: Pendiente');
    expect(backend.requests.filter((r) => /\/documentos\?/.test(r.url)).map((r) => r.url)).toEqual([`/admin/deportistas/${athlete.id}/documentos?page=0&size=100`]);
    expect(fileRequests()).toHaveLength(0); // nothing is fetched (nor audited) until asked
  });

  it.each([
    ['DNI_DEPORTISTA_DORSO', 'DNI – dorso'],
    ['DNI_TUTOR_FRENTE', 'DNI del tutor – frente'],
    ['SOLICITUD_LICENCIA', 'Solicitud de licencia'],
    ['OTRO', 'Otro'],
    ['TIPO_NUEVO', 'Documento'],
  ])('names the type %s «%s»', async (tipo, label) => {
    backend.seedDocument(athlete.id, { tipo });
    await openDetail();
    expect(await within(section()).findByRole('heading', { name: label })).toBeInTheDocument();
    expect(openButton(label)).toBeEnabled();
  });

  it('a tutor DNI type in an athlete list never gets the review controls (U19: the controls follow the owner)', async () => {
    backend.seedDocument(athlete.id, { tipo: 'DNI_TUTOR_FRENTE', estado: 'CARGADO' });
    await openDetail();
    const row = await within(section()).findByRole('listitem');
    expect(within(row).getByRole('button', { name: /Abrir documento/ })).toBeInTheDocument();
    expect(within(row).queryByRole('button', { name: /Aprobar documento|Observar documento/ })).not.toBeInTheDocument();
  });

  it('never shows a reason for a document that is not observed, even if the server sent one', async () => {
    const url = `/admin/deportistas/${athlete.id}/documentos?page=0&size=100`;
    backend.override('get', url, {
      status: 200,
      body: { contenido: [{ id: 'doc-1', tipo: 'APTO_FISICO', estado: 'CARGADO', creadoEn: '2026-03-10T14:00:00Z', motivoObservacion: 'texto viejo' }], pagina: 0, tamanio: 100, totalElementos: 1 },
    });
    await openDetail();
    expect(await within(section()).findByRole('listitem')).toHaveTextContent('Estado: Cargado');
    expect(section()).not.toHaveTextContent('texto viejo');
  });

  it('says so when the athlete has no current documents', async () => {
    await openDetail();
    expect(await within(section()).findByText('Este deportista no tiene documentos vigentes.')).toBeInTheDocument();
    expect(within(section()).queryByRole('button', { name: /Abrir documento/ })).not.toBeInTheDocument();
  });

  it('shows the loading state, then the documents', async () => {
    backend.seedDocument(athlete.id, { tipo: 'DNI_DEPORTISTA_FRENTE' });
    const held = backend.hold('get', `/admin/deportistas/${athlete.id}/documentos?page=0&size=100`);
    await openDetail();
    expect(within(section()).getByText('Cargando los documentos…')).toBeInTheDocument();
    act(() => held.release());
    expect(await within(section()).findByRole('heading', { name: 'DNI – frente' })).toBeInTheDocument();
    expect(within(section()).queryByText('Cargando los documentos…')).not.toBeInTheDocument();
  });

  it('shows an error with a manual retry when the list cannot be loaded, and an unexpected format as an error too', async () => {
    backend.seedDocument(athlete.id, { tipo: 'DNI_DEPORTISTA_FRENTE' });
    const url = `/admin/deportistas/${athlete.id}/documentos?page=0&size=100`;
    backend.override('get', url, { status: 503, body: { codigo: 'SERVICIO_NO_DISPONIBLE', mensaje: 'texto del servidor' } });
    await openDetail();
    expect(await within(section()).findByRole('alert')).toHaveTextContent('No se pudieron cargar los documentos.');
    expect(section()).not.toHaveTextContent('texto del servidor');

    backend.override('get', url, { status: 200, body: { contenido: [{ id: '../x', tipo: 'OTRO', estado: 'CARGADO', creadoEn: 'x' }], totalElementos: 1 } });
    await userEvent.click(within(section()).getByRole('button', { name: 'Reintentar' }));
    expect(await within(section()).findByRole('alert')).toHaveTextContent('No se pudieron cargar los documentos.');

    await userEvent.click(within(section()).getByRole('button', { name: 'Reintentar' }));
    expect(await within(section()).findByRole('heading', { name: 'DNI – frente' })).toBeInTheDocument();
    expect(within(section()).queryByRole('alert')).not.toBeInTheDocument();
  });
});

describe('athlete documents: opening a file (mock backend)', () => {
  it('shows an image inside the page from an in-memory URL, after a CSRF-protected request, and revokes it on «Cerrar»', async () => {
    const document = backend.seedDocument(athlete.id, { tipo: 'DNI_DEPORTISTA_FRENTE' });
    backend.attachDocumentFile(document.id, PNG());
    await openDetail();

    await userEvent.click(await within(section()).findByRole('button', { name: 'Abrir documento: DNI – frente' }));

    const image = await within(section()).findByRole('img', { name: 'Documento: DNI – frente' });
    expect(image).toHaveAttribute('src', 'blob:http://app.test/1');
    expect(screen.getByRole('region', { name: 'Documento abierto: DNI – frente' })).toHaveFocus();
    expect(fileRequests()).toHaveLength(1);
    expect(fileRequests()[0]!.url).toBe(fileUrl(document));
    expect(fileRequests()[0]!.csrfHeader).toMatch(/^csrf-/);
    expect(backend.documentDownloads).toEqual([{ athleteId: athlete.id, documentId: document.id, status: 200 }]);
    expect(revoked).toEqual([]);

    await userEvent.click(within(section()).getByRole('button', { name: 'Cerrar' }));
    expect(within(section()).queryByRole('img')).not.toBeInTheDocument();
    expect(revoked).toEqual(['blob:http://app.test/1']);
    expect(openButton('DNI – frente')).toHaveFocus();
  });

  it('is usable with the keyboard only', async () => {
    const document = backend.seedDocument(athlete.id, { tipo: 'APTO_FISICO' });
    backend.attachDocumentFile(document.id, PNG());
    await openDetail();
    const button = await within(section()).findByRole('button', { name: 'Abrir documento: Apto físico' });
    button.focus();
    await userEvent.keyboard('{Enter}');
    expect(await within(section()).findByRole('img', { name: 'Documento: Apto físico' })).toBeInTheDocument();
    await userEvent.tab();
    expect(within(section()).getByRole('button', { name: 'Cerrar' })).toHaveFocus();
    await userEvent.keyboard('{Enter}');
    expect(within(section()).queryByRole('img')).not.toBeInTheDocument();
  });

  it('does not open a PDF by itself: it offers «Descargar PDF», downloads with the neutral name and revokes the URL right after', async () => {
    const document = backend.seedDocument(athlete.id, { tipo: 'SOLICITUD_LICENCIA' });
    backend.attachDocumentFile(document.id, PDF(), 'attachment; filename="documento-7f3a.pdf"');
    await openDetail();

    await userEvent.click(await within(section()).findByRole('button', { name: 'Abrir documento: Solicitud de licencia' }));
    const region = await screen.findByRole('region', { name: 'Documento abierto: Solicitud de licencia' });
    expect(region).toHaveTextContent('PDF listo.');
    expect(region).toHaveFocus();
    expect(within(region).queryByRole('img')).not.toBeInTheDocument();
    expect(created).toEqual([]); // no URL until the administrator asks for the download
    expect(clicks).toEqual([]);

    await userEvent.click(within(region).getByRole('button', { name: 'Descargar PDF' }));
    expect(clicks).toEqual([{ href: 'blob:http://app.test/1', download: 'documento-7f3a.pdf' }]);
    await waitFor(() => expect(revoked).toEqual(['blob:http://app.test/1']));
    expect(globalThis.document.querySelector('a[download]')).toBeNull(); // the temporary download link is gone (the menu's own «Documentos pendientes» link is not it)
    expect(fileRequests()).toHaveLength(1); // downloading re-uses the bytes in memory: no second audited read
  });

  it.each([
    ['attachment; filename="documento.pdf"', 'documento.pdf'],
    ['attachment; filename=doc_1.pdf', 'doc_1.pdf'],
    ['attachment; filename="../../secreto.pdf"', 'documento.pdf'],
    ['attachment; filename="DNI Lucía Sintética.pdf"', 'documento.pdf'],
    ['attachment; filename="documento.exe"', 'documento.pdf'],
    [null, 'documento.pdf'],
  ])('downloads with a neutral name: %s -> %s', (disposition, expected) => {
    expect(pdfFileName(disposition)).toBe(expected);
  });

  it('falls back to «documento.pdf» when the answer carries no name', async () => {
    const document = backend.seedDocument(athlete.id, { tipo: 'CERTIFICADO_WADA' });
    backend.attachDocumentFile(document.id, PDF(), null);
    await openDetail();
    await userEvent.click(await within(section()).findByRole('button', { name: 'Abrir documento: Certificado WADA' }));
    await userEvent.click(await within(section()).findByRole('button', { name: 'Descargar PDF' }));
    expect(clicks).toEqual([{ href: 'blob:http://app.test/1', download: 'documento.pdf' }]);
  });

  it('revokes the previous URL when another document is opened', async () => {
    const front = backend.seedDocument(athlete.id, { tipo: 'DNI_DEPORTISTA_FRENTE' });
    const back = backend.seedDocument(athlete.id, { tipo: 'DNI_DEPORTISTA_DORSO' });
    backend.attachDocumentFile(front.id, PNG()).attachDocumentFile(back.id, PNG());
    await openDetail();

    await userEvent.click(await within(section()).findByRole('button', { name: 'Abrir documento: DNI – frente' }));
    await within(section()).findByRole('img', { name: 'Documento: DNI – frente' });
    await userEvent.click(openButton('DNI – dorso'));

    const image = await within(section()).findByRole('img', { name: 'Documento: DNI – dorso' });
    expect(image).toHaveAttribute('src', 'blob:http://app.test/2');
    expect(revoked).toEqual(['blob:http://app.test/1']);
    expect(within(section()).getAllByRole('img')).toHaveLength(1);
  });

  it('revokes the URL when the page is left, and drops a file that arrives after it', async () => {
    const shown = backend.seedDocument(athlete.id, { tipo: 'DNI_DEPORTISTA_FRENTE' });
    const late = backend.seedDocument(athlete.id, { tipo: 'DNI_DEPORTISTA_DORSO' });
    backend.attachDocumentFile(shown.id, PNG()).attachDocumentFile(late.id, PNG());
    await openDetail();
    await userEvent.click(await within(section()).findByRole('button', { name: 'Abrir documento: DNI – frente' }));
    await within(section()).findByRole('img');
    await userEvent.click(within(section()).getByRole('button', { name: 'Cerrar' }));
    expect(revoked).toEqual(['blob:http://app.test/1']);

    const held = backend.hold('get', fileUrl(late));
    await userEvent.click(openButton('DNI – dorso'));
    expect(within(section()).getByText('Abriendo el documento…')).toBeInTheDocument();
    // While one file is on its way no other can be requested: every read is an audited access.
    for (const button of within(section()).getAllByRole('button', { name: /Abrir documento/ })) expect(button).toBeDisabled();

    await userEvent.click(screen.getByRole('link', { name: /Volver al listado/ }));
    await screen.findByRole('heading', { level: 1, name: 'Deportistas' });
    act(() => held.release());
    await new Promise((resolve) => setTimeout(resolve, 20));
    expect(created).toEqual(['blob:http://app.test/1']); // the late file never became a URL
  });

  it('revokes the URL of a shown image when the page is left', async () => {
    const document = backend.seedDocument(athlete.id, { tipo: 'DNI_DEPORTISTA_FRENTE' });
    backend.attachDocumentFile(document.id, PNG());
    await openDetail();
    await userEvent.click(await within(section()).findByRole('button', { name: 'Abrir documento: DNI – frente' }));
    await within(section()).findByRole('img');
    await userEvent.click(screen.getByRole('link', { name: /Volver al listado/ }));
    await screen.findByRole('heading', { level: 1, name: 'Deportistas' });
    expect(revoked).toEqual(['blob:http://app.test/1']);
  });

  it('keeps the file out of the query cache, the address, the storages and the console', async () => {
    const consoleSpies = (['log', 'info', 'warn', 'error', 'debug'] as const).map((name) => vi.spyOn(console, name));
    const image = backend.seedDocument(athlete.id, { tipo: 'DNI_DEPORTISTA_FRENTE' });
    const pdf = backend.seedDocument(athlete.id, { tipo: 'APTO_FISICO' });
    backend.attachDocumentFile(image.id, PNG()).attachDocumentFile(pdf.id, PDF());
    const { queryClient } = await openDetail();

    await userEvent.click(await within(section()).findByRole('button', { name: 'Abrir documento: DNI – frente' }));
    await within(section()).findByRole('img');
    await userEvent.click(openButton('Apto físico'));
    await userEvent.click(await within(section()).findByRole('button', { name: 'Descargar PDF' }));

    const cached = queryClient.getQueryCache().getAll().map((query) => query.state.data);
    const containsFile = (value: unknown): boolean =>
      value instanceof Blob || (typeof value === 'string' && value.startsWith('blob:')) || (typeof value === 'object' && value !== null && Object.values(value).some(containsFile));
    expect(cached.some(containsFile)).toBe(false);
    expect(queryClient.getMutationCache().getAll()).toHaveLength(0);
    expect(screen.getByTestId('location').textContent).toBe(`/admin/deportistas/${athlete.id}`);
    expect(localStorage.length).toBe(0);
    expect(sessionStorage.length).toBe(0);
    for (const spy of consoleSpies) {
      expect(spy.mock.calls.flat().some((arg) => String(arg).includes('blob:') || arg instanceof Blob)).toBe(false);
    }
  });

  it('says what the system records, and that a download leaves a copy on the device', async () => {
    await openDetail();
    expect(section()).toHaveTextContent(
      'Cada vez que se abre o se descarga un documento, el sistema de la escuela lo registra. Al descargar un PDF se guarda una copia en este dispositivo.',
    );
  });
});

describe('athlete documents: failures (mock backend)', () => {
  it.each([
    [502, 'INTEGRIDAD_FALLIDA', 'El archivo guardado no superó la verificación de integridad, por eso no se muestra.'],
    [409, 'INTEGRIDAD_NO_VERIFICABLE', 'No es posible verificar la integridad de este documento, por eso no se muestra.'],
    [503, 'DESCARGA_NO_DISPONIBLE', 'La consulta de documentos no está disponible en este momento. Se puede volver a intentar más tarde.'],
    [429, 'DESCARGA_OCUPADA', 'Hay otros documentos abriéndose en este momento. Se puede volver a intentar en unos instantes.'],
    [404, 'DOCUMENTO_NO_ENCONTRADO', 'El documento ya no está disponible. Es posible que haya sido reemplazado.'],
    [403, 'ACCESO_DENEGADO', 'Esta cuenta no tiene permiso para abrir este documento.'],
    [403, 'CSRF_INVALIDO', 'No se pudo validar la solicitud. Es necesario recargar la página.'],
  ])('HTTP %i %s: its own message, nothing shown and no automatic retry', async (status, codigo, message) => {
    const document = backend.seedDocument(athlete.id, { tipo: 'DNI_DEPORTISTA_FRENTE' });
    backend.override('get', fileUrl(document), { status, body: { codigo, mensaje: 'texto del servidor', detalles: [] } });
    await openDetail();

    await userEvent.click(await within(section()).findByRole('button', { name: 'Abrir documento: DNI – frente' }));

    const region = await screen.findByRole('region', { name: 'Documento abierto: DNI – frente' });
    expect(within(region).getByRole('alert')).toHaveTextContent(message);
    expect(region).toHaveFocus();
    expect(section()).not.toHaveTextContent('texto del servidor');
    expect(within(section()).queryByRole('img')).not.toBeInTheDocument();
    expect(within(section()).queryByRole('button', { name: 'Descargar PDF' })).not.toBeInTheDocument();
    expect(created).toEqual([]);
    await new Promise((resolve) => setTimeout(resolve, 20));
    expect(fileRequests()).toHaveLength(1);

    // Retrying is the administrator's choice.
    await userEvent.click(openButton('DNI – frente'));
    expect(await within(section()).findByRole('img', { name: 'Documento: DNI – frente' })).toBeInTheDocument();
    expect(fileRequests()).toHaveLength(2);
  });

  it('a file of a type outside the allow-list is never rendered nor offered', async () => {
    const document = backend.seedDocument(athlete.id, { tipo: 'OTRO' });
    backend.attachDocumentFile(document.id, new Blob(['<script>alert(1)</script>'], { type: 'text/html' }));
    await openDetail();
    await userEvent.click(await within(section()).findByRole('button', { name: 'Abrir documento: Otro' }));
    expect(await within(section()).findByRole('alert')).toHaveTextContent('El archivo recibido tiene un formato que no se puede mostrar.');
    expect(created).toEqual([]);
    expect(within(section()).queryByRole('button', { name: 'Descargar PDF' })).not.toBeInTheDocument();
  });

  it('a network failure says so', async () => {
    const document = backend.seedDocument(athlete.id, { tipo: 'DNI_DEPORTISTA_FRENTE' });
    backend.override('get', fileUrl(document), 'network');
    await openDetail();
    await userEvent.click(await within(section()).findByRole('button', { name: 'Abrir documento: DNI – frente' }));
    expect(await within(section()).findByRole('alert')).toHaveTextContent('No se pudo conectar con el servidor.');
    expect(created).toEqual([]);
  });

  it('a request without the CSRF header is refused by the server', async () => {
    const document = backend.seedDocument(athlete.id, { tipo: 'DNI_DEPORTISTA_FRENTE' });
    backend.signIn('marcos@example.com');
    const { http } = await import('@/lib/http');
    await expect(http.get(fileUrl(document), { responseType: 'blob' })).rejects.toMatchObject({ status: 403 });
    expect(backend.documentDownloads).toEqual([]); // refused before the (mock) audited read
  });

  it('an expired session (401 NO_AUTENTICADO) ends in the admin login with nothing shown', async () => {
    const document = backend.seedDocument(athlete.id, { tipo: 'DNI_DEPORTISTA_FRENTE' });
    backend.attachDocumentFile(document.id, PNG());
    await openDetail();
    const button = await within(section()).findByRole('button', { name: 'Abrir documento: DNI – frente' });
    backend.expireSession();
    await userEvent.click(button);
    expect(await screen.findByRole('heading', { name: 'Acceso administrativo' })).toBeInTheDocument();
    expect(screen.queryByRole('img')).not.toBeInTheDocument();
    expect(created).toEqual([]);
  });

  it('a FAMILIA session never reaches the page nor requests documents', async () => {
    backend.seedDocument(athlete.id, { tipo: 'DNI_DEPORTISTA_FRENTE' });
    backend.signIn('lucia@example.com');
    renderApp(`/admin/deportistas/${athlete.id}`);
    expect(await screen.findByRole('heading', { name: 'No tenés permiso para ver esta sección' })).toBeInTheDocument();
    expect(backend.requests.filter((r) => r.url.includes('/documentos'))).toEqual([]);
  });
});

const frontReview = { id: 'frente', tipo: 'DNI_DEPORTISTA_FRENTE', estado: 'CARGADO', creadoEn: '2026-03-10T14:00:00Z', vigente: true, version: '9007199254740993' } as const;
const reviewUrl = () => `/admin/deportistas/${athlete.id}/documentos/${frontReview.id}/revision`;
const documentListUrl = () => `/admin/deportistas/${athlete.id}/documentos?page=0&size=100`;
const reviewWrites = () => backend.writes().filter((request) => request.url === reviewUrl());
const serveReviewRows = (...rows: unknown[]) => backend.override('get', documentListUrl(), {
  status: 200,
  body: { contenido: rows, pagina: 0, tamanio: 100, totalElementos: rows.length },
});

describe('revisión administrativa de DNI', () => {
  it('ofrece acciones de revisión para el DNI cargado y no para otros tipos', async () => {
    const url = '/admin/deportistas/' + athlete.id + '/documentos?page=0&size=100';
    backend.override('get', url, { status: 200, body: { contenido: [
      { id: 'frente', tipo: 'DNI_DEPORTISTA_FRENTE', estado: 'CARGADO', creadoEn: '2026-03-10T14:00:00Z', vigente: true, version: '0' },
      { id: 'apto', tipo: 'APTO_FISICO', estado: 'CARGADO', creadoEn: '2026-03-10T14:00:00Z', vigente: true, version: '0' },
    ], pagina: 0, tamanio: 100, totalElementos: 2 } });
    await openDetail();
    expect(await within(section()).findByRole('button', { name: 'Aprobar documento: DNI – frente' })).toBeEnabled();
    expect(within(section()).getByRole('button', { name: 'Observar documento: DNI – frente' })).toBeEnabled();
    expect(within(section()).queryByRole('button', { name: /Observar documento: Apto/ })).not.toBeInTheDocument();
  });

  it('exige motivo y confirmación expresa para revocar una aprobación; envía la versión como cadena una sola vez', async () => {
    backend.seedDocument(athlete.id, { ...frontReview, estado: 'APROBADO' });
    const held = backend.hold('post', reviewUrl());
    await openDetail();
    expect(within(section()).queryByRole('button', { name: /Aprobar documento/ })).not.toBeInTheDocument();
    await userEvent.click(await within(section()).findByRole('button', { name: /Observar documento/ }));
    const dialog = screen.getByRole('dialog');
    expect(dialog).toHaveTextContent('Esta decisión revoca la aprobación');
    expect(within(dialog).getByRole('textbox')).toHaveFocus();
    const confirm = within(dialog).getByRole('button', { name: 'Confirmar observación' });
    await userEvent.click(confirm);
    expect(await within(dialog).findByRole('alert')).toHaveTextContent('Ingresá un motivo');
    expect(reviewWrites()).toHaveLength(0);
    await userEvent.type(within(dialog).getByRole('textbox'), '  Recorte deficiente  ');
    await userEvent.dblClick(confirm);
    await waitFor(() => expect(reviewWrites()).toHaveLength(1));
    expect(typeof reviewWrites()[0]?.csrfHeader).toBe('string');
    expect(reviewWrites()[0]).toMatchObject({
      body: { estado: 'OBSERVADO', versionEsperada: '9007199254740993', motivoObservacion: 'Recorte deficiente' },
    });
    expect(within(dialog).getByRole('button', { name: 'Guardando…' })).toBeDisabled();
    act(() => held.release());
    expect(await within(section()).findByText('Documento observado.')).toBeInTheDocument();
    expect(within(section()).getByText(/Motivo de la observación: Recorte/)).toBeInTheDocument();
    expect(within(section()).queryByRole('button', { name: /Aprobar|Observar|Reemplazar/ })).not.toBeInTheDocument();
    expect(reviewWrites()).toHaveLength(1);
  });

  it('recarga después de 409 y no reenvía la decisión con una nueva versión', async () => {
    serveReviewRows(frontReview);
    serveReviewRows({ ...frontReview, estado: 'APROBADO', version: '9007199254740994' });
    backend.override('post', reviewUrl(), { status: 409, body: { codigo: 'EDICION_DESACTUALIZADA' } });
    await openDetail();
    await userEvent.click(await within(section()).findByRole('button', { name: /Aprobar documento/ }));
    await userEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Confirmar aprobación' }));
    expect(await within(section()).findByRole('alert')).toHaveTextContent('volvé a decidir');
    await waitFor(() => expect(within(section()).queryByRole('button', { name: /Aprobar documento/ })).not.toBeInTheDocument());
    expect(within(section()).getByRole('button', { name: /Observar documento/ })).toBeEnabled();
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(reviewWrites()).toHaveLength(1);
  });

  it('no envía si la sesión cambia durante CSRF y no restaura datos de esa sesión', async () => {
    serveReviewRows(frontReview);
    const { queryClient } = await openDetail();
    await userEvent.click(await within(section()).findByRole('button', { name: /Aprobar documento/ }));
    const held = backend.hold('get', '/auth/csrf');
    const before = backend.requests.filter((request) => request.url === '/auth/csrf').length;
    await userEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Confirmar aprobación' }));
    await waitFor(() => expect(backend.requests.filter((request) => request.url === '/auth/csrf')).toHaveLength(before + 1));
    act(() => { queryClient.setQueryData(['session'], null); });
    await screen.findByRole('heading', { name: 'Acceso administrativo' });
    await act(() => { held.release(); return Promise.resolve(); });
    expect(reviewWrites()).toHaveLength(0);
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('descarta una respuesta de revisión que llega después de salir del deportista', async () => {
    backend.seedDocument(athlete.id, frontReview);
    const newAthlete = backend.addAdminAthlete({ nombre: 'Inés', apellido: 'Otra', dni: '30111323' });
    const held = backend.hold('post', reviewUrl());
    const { queryClient } = await openDetail();
    await userEvent.click(await within(section()).findByRole('button', { name: /Aprobar documento/ }));
    await userEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Confirmar aprobación' }));
    await waitFor(() => expect(reviewWrites()).toHaveLength(1));
    await userEvent.click(screen.getByRole('link', { name: /Volver al listado/ }));
    await userEvent.click((await screen.findAllByRole('link', { name: /Otra, Inés/ }))[0]!);
    await screen.findByRole('heading', { name: 'Inés Otra' });
    expect(screen.getByTestId('location')).toHaveTextContent(newAthlete.id);
    await act(() => { held.release(); return Promise.resolve(); });
    expect(queryClient.getQueryData(['admin', 'athlete-documents', athlete.id])).toBeUndefined();
    expect(screen.queryByText('Documento aprobado.')).not.toBeInTheDocument();
  });
});
