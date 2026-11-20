import { cleanup, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { FakeBackend } from '@/test/fakeBackend';
import { createBackend, family, renderApp } from '@/test/render';
import { readBytes } from './image-normalization';
import type * as ImageNormalizationModule from './image-normalization';

type ImageNormalization = typeof ImageNormalizationModule;

// MOCK backend (src/test/fakeBackend.ts) inside the REAL app. jsdom has no Canvas and no object URLs, so the image
// re-encoding is replaced at the browser boundary (PDFs use the real code). The real re-encoding, the real backend and
// the bytes that reach Storage are proven by e2e/documentos-dni.real.spec.ts.
vi.mock('./image-normalization', async (importOriginal) => {
  const real = await importOriginal<ImageNormalization>();
  return {
    ...real,
    prepareDocument: vi.fn(async (file: File, maxBytes: number) => {
      if (file.type === 'application/pdf') return real.prepareDocument(file, maxBytes);
      if (file.name.includes('heic')) return { ok: false as const, reason: 'decode-failed' as const };
      const blob = new Blob([await real.readBytes(file)], { type: 'image/jpeg' });
      const small = file.name.includes('chica');
      return {
        ok: true as const,
        prepared: { kind: 'image' as const, blob, fileName: 'documento.jpg' as const, width: small ? 800 : 2400, height: small ? 600 : 1800, lowResolution: small },
      };
    }),
  };
});

let backend: FakeBackend;
const created: string[] = [];
const revoked: string[] = [];

const photo = (name = 'IMG_20260310_casa.jpg') => new File([new Uint8Array([0xff, 0xd8, 0xff, 0xe0, 1, 2, 3, 0xff, 0xd9])], name, { type: 'image/jpeg' });
const pdf = () => new File(['%PDF-1.7\nsintetico'], 'DNI Ana Paz.pdf', { type: 'application/pdf' });

function own() {
  const athlete = backend.addAthlete({ nombre: 'Ana', apellido: 'Paz', edad: 9, activo: true, vigente: null });
  backend.linkAthlete('familia-1', athlete.id);
  return athlete;
}

const uploadsUrl = (id: string) => `/familia/deportistas/${id}/documentos`;
const section = () => screen.findByRole('region', { name: 'Documentos del deportista' });
const slot = async (name: 'DNI – frente' | 'DNI – dorso') => within(await section()).findByRole('article', { name });

async function openSheet(id: string) {
  renderApp(`/familia/deportistas/${id}`);
  return section();
}

async function choosePhoto(name: 'DNI – frente' | 'DNI – dorso', file = photo()) {
  const user = userEvent.setup();
  await user.upload(within(await slot(name)).getByLabelText(`Sacar foto del ${name}`), file);
  return user;
}

beforeEach(() => {
  backend = createBackend();
  backend.signIn(family.email);
  created.length = 0;
  revoked.length = 0;
  vi.spyOn(URL, 'createObjectURL').mockImplementation(() => {
    const url = `blob:preview-${created.length + 1}`;
    created.push(url);
    return url;
  });
  vi.spyOn(URL, 'revokeObjectURL').mockImplementation((url) => void revoked.push(url));
});

afterEach(() => {
  cleanup();
  backend.clearOverrides();
});

describe('documents of the athlete: what the family sees', () => {
  it('with uploads disabled (the default) shows both DNI sides as missing, says so, and offers no upload', async () => {
    const athlete = own();
    await openSheet(athlete.id);

    for (const name of ['DNI – frente', 'DNI – dorso'] as const) {
      const card = await slot(name);
      expect(within(card).getByText('Falta')).toBeInTheDocument();
      expect(within(card).getByText('La carga de documentos no está habilitada por el momento.')).toBeInTheDocument();
      expect(within(card).queryByLabelText(/Sacar foto|Elegir archivo/)).not.toBeInTheDocument();
    }
    expect(backend.requests.some((r) => r.url === '/familia/documentos/disponibilidad')).toBe(true);
    expect(backend.writes()).toHaveLength(0);
  });

  it('shows the state the server reports, and the reason ONLY for an observed document', async () => {
    const athlete = own();
    backend.seedDocument(athlete.id, { tipo: 'DNI_DEPORTISTA_FRENTE', estado: 'APROBADO' });
    backend.seedDocument(athlete.id, { tipo: 'DNI_DEPORTISTA_DORSO', estado: 'OBSERVADO', motivoObservacion: 'La foto está borrosa.' });
    await openSheet(athlete.id);

    const front = await slot('DNI – frente');
    expect(within(front).getByText('Aprobado')).toBeInTheDocument();
    expect(within(front).getByText(/Cargado el/)).toBeInTheDocument();
    expect(within(front).queryByText(/Motivo/)).not.toBeInTheDocument();
    const back = await slot('DNI – dorso');
    expect(within(back).getByText('Observado')).toBeInTheDocument();
    expect(within(back).getByText('La foto está borrosa.')).toBeInTheDocument();
    expect(within(back).getByText('Comunicate con el club para resolverlo.')).toBeInTheDocument();
  });
});

describe('uploading a DNI side', () => {
  beforeEach(() => void backend.enableDocumentUpload());

  it('previews the prepared photo, sends it with a neutral name and a UUID key, and then shows it in review', async () => {
    const athlete = own();
    await openSheet(athlete.id);
    const user = await choosePhoto('DNI – frente');

    const front = await slot('DNI – frente');
    expect(within(front).getByRole('img', { name: 'Vista previa del DNI – frente' })).toHaveAttribute('src', 'blob:preview-1');
    expect(within(front).getByText('Revisá que se lean bien todos los datos antes de enviar.')).toBeInTheDocument();
    await user.click(within(front).getByRole('button', { name: 'Enviar' }));

    expect(await within(front).findByText('Documento enviado. El club lo va a revisar.')).toBeInTheDocument();
    expect(await within(front).findByText('En revisión')).toBeInTheDocument();
    const [upload] = backend.documentUploads;
    expect(upload).toMatchObject({ athleteId: athlete.id, tipo: 'DNI_DEPORTISTA_FRENTE', fileName: 'documento.jpg', type: 'image/jpeg', status: 201 });
    expect(upload?.idempotencyKey).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/);
    expect(revoked).toEqual(['blob:preview-1']); // the preview is released once the document is sent
    // The other side is untouched and still offers its own upload.
    expect(within(await slot('DNI – dorso')).getByText('Falta')).toBeInTheDocument();
  });

  it('after a LOST response, «Reintentar» re-sends the SAME bytes with the SAME key and nothing is stored twice', async () => {
    const athlete = own();
    backend.override('post', uploadsUrl(athlete.id), 'lost');
    await openSheet(athlete.id);
    const user = await choosePhoto('DNI – frente');
    const front = await slot('DNI – frente');
    await user.click(within(front).getByRole('button', { name: 'Enviar' }));

    const alert = await within(front).findByRole('alert');
    expect(alert).toHaveTextContent('No pudimos confirmar si el documento se guardó');
    expect(alert).toHaveTextContent('no se va a duplicar');
    await user.click(within(front).getByRole('button', { name: 'Reintentar' }));

    expect(await within(front).findByText('En revisión')).toBeInTheDocument();
    const [first, second] = backend.documentUploads;
    expect(backend.documentUploads.map((u) => u.status)).toEqual([201, 200]);
    expect(second?.idempotencyKey).toBe(first?.idempotencyKey);
    expect(await readBytes(second!.blob)).toEqual(await readBytes(first!.blob));
    expect(backend.documentsOf(athlete.id)).toHaveLength(1);
  });

  it('after an UNCERTAIN result (503) the retry keeps the key, and the message never says it failed or succeeded', async () => {
    const athlete = own();
    backend.override('post', uploadsUrl(athlete.id), { status: 503, body: { codigo: 'RESULTADO_CARGA_INCIERTO', mensaje: 'x' } });
    await openSheet(athlete.id);
    const user = await choosePhoto('DNI – dorso');
    const back = await slot('DNI – dorso');
    await user.click(within(back).getByRole('button', { name: 'Enviar' }));

    const alert = await within(back).findByRole('alert');
    expect(alert.textContent).not.toMatch(/falló|se subió correctamente/i);
    await user.click(within(back).getByRole('button', { name: 'Reintentar' }));
    expect(await within(back).findByText('En revisión')).toBeInTheDocument();

    const posts = backend.writes().filter((w) => w.url === uploadsUrl(athlete.id));
    expect(posts).toHaveLength(2);
    expect(posts[1]?.idempotencyKey).toBe(posts[0]?.idempotencyKey);
  });

  it('a rejected file (413) drops the attempt: the next file gets a NEW key', async () => {
    const athlete = own();
    backend.override('post', uploadsUrl(athlete.id), { status: 413, body: { codigo: 'ARCHIVO_DEMASIADO_GRANDE', mensaje: 'x' } });
    await openSheet(athlete.id);
    let user = await choosePhoto('DNI – frente');
    let front = await slot('DNI – frente');
    await user.click(within(front).getByRole('button', { name: 'Enviar' }));

    expect(await within(front).findByRole('alert')).toHaveTextContent('El archivo supera el tamaño máximo (5 MB)');
    expect(within(front).queryByRole('button', { name: 'Reintentar' })).not.toBeInTheDocument();
    user = await choosePhoto('DNI – frente', photo('otra.jpg'));
    front = await slot('DNI – frente');
    await user.click(within(front).getByRole('button', { name: 'Enviar' }));
    expect(await within(front).findByText('En revisión')).toBeInTheDocument();

    const [rejected, accepted] = backend.writes().filter((w) => w.url === uploadsUrl(athlete.id));
    expect(accepted?.idempotencyKey).not.toBe(rejected?.idempotencyKey);
  });

  it('a 409 drops the attempt and reloads the documents (for example another tutor uploaded it first)', async () => {
    const athlete = own();
    await openSheet(athlete.id);
    const user = await choosePhoto('DNI – frente');
    backend.seedDocument(athlete.id, { tipo: 'DNI_DEPORTISTA_FRENTE', estado: 'CARGADO' }); // uploaded meanwhile from another device
    const front = await slot('DNI – frente');
    await user.click(within(front).getByRole('button', { name: 'Enviar' }));

    expect(await within(front).findByText(/Actualizamos la información/)).toBeInTheDocument();
    expect(await within(front).findByText('En revisión')).toBeInTheDocument();
    expect(backend.documentUploads.map((u) => u.status)).toEqual([409]);
    expect(backend.documentsOf(athlete.id)).toHaveLength(1);
  });

  it('a PDF goes as it is, with a neutral name and no image preview', async () => {
    const athlete = own();
    await openSheet(athlete.id);
    const user = userEvent.setup();
    await user.upload(within(await slot('DNI – dorso')).getByLabelText('Elegir archivo para el DNI – dorso'), pdf());
    const back = await slot('DNI – dorso');

    expect(await within(back).findByText(/PDF listo para enviar/)).toBeInTheDocument();
    expect(within(back).queryByRole('img')).not.toBeInTheDocument();
    await user.click(within(back).getByRole('button', { name: 'Enviar' }));
    expect(await within(back).findByText('En revisión')).toBeInTheDocument();
    expect(backend.documentUploads[0]).toMatchObject({ fileName: 'documento.pdf', type: 'application/pdf' });
    expect(await readBytes(backend.documentUploads[0]!.blob)).toEqual(await readBytes(pdf()));
  });

  it('warns about a low resolution without blocking the upload, and never calls it a legibility guarantee', async () => {
    const athlete = own();
    await openSheet(athlete.id);
    await choosePhoto('DNI – frente', photo('foto-chica.jpg'));
    const front = await slot('DNI – frente');

    expect(within(front).getByText(/poca resolución y puede no leerse bien/)).toBeInTheDocument();
    expect(within(front).getByRole('button', { name: 'Enviar' })).toBeEnabled();
  });

  it('an image the browser cannot open (HEIC) is explained, and nothing is sent', async () => {
    const athlete = own();
    await openSheet(athlete.id);
    await choosePhoto('DNI – frente', new File(['x'], 'IMG_1234.heic', { type: 'image/heic' }));
    const front = await slot('DNI – frente');

    expect(await within(front).findByRole('alert')).toHaveTextContent(/formato HEIC/);
    expect(backend.writes()).toHaveLength(0);
  });

  it('«Elegir otro» releases the preview; nothing is kept in the address or the browser storage', async () => {
    const athlete = own();
    await openSheet(athlete.id);
    const user = await choosePhoto('DNI – frente');
    await user.click(within(await slot('DNI – frente')).getByRole('button', { name: 'Elegir otro' }));

    expect(revoked).toEqual(['blob:preview-1']);
    expect(within(await slot('DNI – frente')).getByLabelText('Sacar foto del DNI – frente')).toBeInTheDocument();
    expect(screen.getByTestId('location')).toHaveTextContent(`/familia/deportistas/${athlete.id}`);
    expect(window.localStorage.length + window.sessionStorage.length).toBe(0);
  });

  it('a slow upload reports its progress', async () => {
    const athlete = own();
    await openSheet(athlete.id);
    const user = await choosePhoto('DNI – frente');
    const held = backend.hold('post', uploadsUrl(athlete.id));
    const front = await slot('DNI – frente');
    await user.click(within(front).getByRole('button', { name: 'Enviar' }));

    expect(await within(front).findByText(/Enviando…/)).toBeInTheDocument();
    expect(within(front).queryByRole('button', { name: 'Enviar' })).not.toBeInTheDocument();
    held.release();
    await waitFor(() => expect(within(front).getByText('En revisión')).toBeInTheDocument());
  });
});
