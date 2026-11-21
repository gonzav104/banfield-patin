import { cleanup, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { FakeBackend } from '@/test/fakeBackend';
import { createBackend, family, renderApp } from '@/test/render';
import { readBytes } from './image-normalization';
import type * as ImageNormalizationModule from './image-normalization';

type ImageNormalization = typeof ImageNormalizationModule;

// MOCK backend (src/test/fakeBackend.ts) inside the REAL app: the documents of a TUTOR of the family (U19). The real
// pipeline, the authorization and the audit are proven by the PostgreSQL tests and e2e/documentos-tutor.real.spec.ts.
vi.mock('./image-normalization', async (importOriginal) => {
  const real = await importOriginal<ImageNormalization>();
  return {
    ...real,
    prepareDocument: vi.fn(async (file: File, maxBytes: number) => {
      if (file.type === 'application/pdf') return real.prepareDocument(file, maxBytes);
      const blob = new Blob([await real.readBytes(file)], { type: 'image/jpeg' });
      return { ok: true as const, prepared: { kind: 'image' as const, blob, fileName: 'documento.jpg' as const, width: 2400, height: 1800, lowResolution: false } };
    }),
  };
});

let backend: FakeBackend;
const TUTOR = { id: '11111111-1111-4111-8111-111111111111', nombre: 'Marta', apellido: 'Gómez', parentesco: 'Madre', telefono: null, email: null };
const OTHER = { id: '22222222-2222-4222-8222-222222222222', nombre: 'Luis', apellido: 'Gómez', parentesco: 'Padre', telefono: null, email: null };

const photo = (name = 'IMG_tutor.jpg') => new File([new Uint8Array([0xff, 0xd8, 0xff, 0xe0, 1, 2, 3, 0xff, 0xd9])], name, { type: 'image/jpeg' });
const section = () => screen.findByRole('region', { name: 'Documentos del tutor' });
const slot = async (name: 'DNI – frente' | 'DNI – dorso') => within(await section()).findByRole('article', { name });
const docsUrl = (id: string) => `/familia/tutores/${id}/documentos`;
const replaceUrl = (id: string, documentId: string) => `/familia/tutores/${id}/documentos/${documentId}/reemplazo`;

beforeEach(() => {
  backend = createBackend();
  backend.addPortalFamily('familia-1', 'Familia Gómez', [TUTOR, OTHER]);
  backend.signIn(family.email);
  vi.spyOn(URL, 'createObjectURL').mockImplementation(() => 'blob:preview');
  vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => undefined);
});

afterEach(() => {
  cleanup();
  backend.clearOverrides();
});

describe('tutor documents: navigation and states', () => {
  it('the family card links each tutor to their documents, and the page names the tutor and shows both DNI sides as missing', async () => {
    backend.enableDocumentUpload();
    renderApp('/familia');
    const link = await screen.findByRole('link', { name: 'Documentos de Marta Gómez' });
    expect(screen.getByRole('link', { name: 'Documentos de Luis Gómez' })).toBeInTheDocument();
    await userEvent.click(link);

    expect(await screen.findByRole('heading', { level: 1, name: 'Marta Gómez' })).toBeInTheDocument();
    expect(screen.getByText('Madre')).toBeInTheDocument();
    expect(within(await slot('DNI – frente')).getByText('Falta')).toBeInTheDocument();
    expect(within(await slot('DNI – dorso')).getByText('Falta')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /Volver a tu familia/ })).toHaveAttribute('href', '/familia');
    // Only the documents of THIS tutor are requested.
    expect(backend.requests.filter((r) => r.url.includes('/documentos')).map((r) => r.url)).toContain(`${docsUrl(TUTOR.id)}?page=0&size=100`);
  });

  it.each([
    ['a tutor that is not in the family', '33333333-3333-4333-8333-333333333333'],
    ['an id that is not a UUID', 'no-es-un-uuid'],
  ])('%s shows ONE notice and requests no document', async (_name, id) => {
    backend.enableDocumentUpload();
    renderApp(`/familia/tutores/${id}`);
    expect(await screen.findByText('No encontramos este tutor')).toBeInTheDocument();
    expect(backend.requests.some((r) => r.url.includes('/documentos'))).toBe(false);
  });

  it('without uploads enabled it shows the states and says why nothing can be sent', async () => {
    backend.seedDocument(TUTOR.id, { tipo: 'DNI_TUTOR_FRENTE', estado: 'APROBADO' });
    renderApp(`/familia/tutores/${TUTOR.id}`);
    expect(within(await slot('DNI – frente')).getByText('Aprobado')).toBeInTheDocument();
    expect(within(await slot('DNI – dorso')).getByText('Falta')).toBeInTheDocument();
    expect(screen.getByText('La carga de documentos no está habilitada por el momento.', { exact: false })).toBeInTheDocument();
    expect(screen.queryByLabelText(/Sacar foto/)).not.toBeInTheDocument();
  });
});

describe('tutor documents: upload and replacement', () => {
  beforeEach(() => void backend.enableDocumentUpload());

  it('uploads a tutor DNI side to the tutor route with the tutor type and a UUID key, and shows it in review', async () => {
    renderApp(`/familia/tutores/${TUTOR.id}`);
    const front = await slot('DNI – frente');
    const user = userEvent.setup();
    await user.upload(within(front).getByLabelText('Sacar foto del DNI – frente'), photo());
    await user.click(await within(front).findByRole('button', { name: 'Enviar' }));

    expect(await within(front).findByText('En revisión')).toBeInTheDocument();
    const [upload] = backend.documentUploads;
    expect(upload).toMatchObject({ athleteId: TUTOR.id, tipo: 'DNI_TUTOR_FRENTE', status: 201, fileName: 'documento.jpg' });
    expect(upload?.idempotencyKey).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/);
    expect(backend.requests.some((r) => r.method === 'post' && r.url.startsWith('/familia/deportistas/'))).toBe(false); // the athlete routes are untouched
    expect(backend.requests.some((r) => r.method === 'post' && r.url === docsUrl(TUTOR.id))).toBe(true);
    // The other tutor of the family is not touched.
    expect(backend.documentsOf(OTHER.id)).toHaveLength(0);
  });

  it('after a LOST response «Reintentar» re-sends the SAME bytes with the SAME key and stores the tutor DNI once', async () => {
    backend.override('post', docsUrl(TUTOR.id), 'lost');
    renderApp(`/familia/tutores/${TUTOR.id}`);
    const back = await slot('DNI – dorso');
    const user = userEvent.setup();
    await user.upload(within(back).getByLabelText('Sacar foto del DNI – dorso'), photo());
    await user.click(await within(back).findByRole('button', { name: 'Enviar' }));
    expect(await within(back).findByRole('alert')).toHaveTextContent('No pudimos confirmar si el documento se guardó');
    await user.click(within(back).getByRole('button', { name: 'Reintentar' }));

    expect(await within(back).findByText('En revisión')).toBeInTheDocument();
    const [first, second] = backend.documentUploads;
    expect(backend.documentUploads.map((u) => u.status)).toEqual([201, 200]);
    expect(second?.idempotencyKey).toBe(first?.idempotencyKey);
    expect(await readBytes(second!.blob)).toEqual(await readBytes(first!.blob));
    expect(backend.documentsOf(TUTOR.id).filter((d) => d.tipo === 'DNI_TUTOR_DORSO')).toHaveLength(1);
  });

  it('an OBSERVED tutor DNI shows the reason and is replaced through the tutor replacement route; approved ones offer nothing', async () => {
    backend.seedDocument(TUTOR.id, { tipo: 'DNI_TUTOR_FRENTE', estado: 'APROBADO' });
    const observed = backend.seedDocument(TUTOR.id, { tipo: 'DNI_TUTOR_DORSO', estado: 'OBSERVADO', motivoObservacion: 'El dorso está cortado.' });
    renderApp(`/familia/tutores/${TUTOR.id}`);

    const front = await slot('DNI – frente');
    expect(within(front).queryByLabelText(/Sacar foto|Elegir archivo/)).not.toBeInTheDocument();
    const back = await slot('DNI – dorso');
    expect(within(back).getByText('El dorso está cortado.')).toBeInTheDocument();
    expect(within(back).getByText('Subí un archivo nuevo para reemplazarlo.')).toBeInTheDocument();
    const user = userEvent.setup();
    await user.upload(within(back).getByLabelText('Sacar foto del DNI – dorso'), photo());
    await user.click(await within(back).findByRole('button', { name: 'Enviar' }));

    expect(await within(back).findByText('En revisión')).toBeInTheDocument();
    const [upload] = backend.documentUploads;
    expect(upload).toMatchObject({ athleteId: TUTOR.id, tipo: 'DNI_TUTOR_DORSO', status: 201, replaces: observed.id });
    const post = backend.requests.find((r) => r.method === 'post' && r.url === replaceUrl(TUTOR.id, observed.id))!;
    expect([...(post.body as FormData).keys()]).toEqual(['archivo']);
    expect(backend.documentsOf(TUTOR.id).filter((d) => d.tipo === 'DNI_TUTOR_DORSO')).toHaveLength(1);
  });

  it('a 404 TUTOR_NO_ENCONTRADO on an upload says the tutor is not found and reloads the documents', async () => {
    backend.override('post', docsUrl(TUTOR.id), { status: 404, body: { codigo: 'TUTOR_NO_ENCONTRADO', mensaje: 'x' } });
    renderApp(`/familia/tutores/${TUTOR.id}`);
    const front = await slot('DNI – frente');
    const user = userEvent.setup();
    await user.upload(within(front).getByLabelText('Sacar foto del DNI – frente'), photo());
    await user.click(await within(front).findByRole('button', { name: 'Enviar' }));
    expect(await within(front).findByText('No encontramos este tutor. Volvé a tu familia.')).toBeInTheDocument();
    await waitFor(() => expect(backend.requests.filter((r) => r.method === 'get' && r.url.startsWith(docsUrl(TUTOR.id))).length).toBeGreaterThan(1));
  });
});
