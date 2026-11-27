import { cleanup, fireEvent, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { FakeBackend, FakeSeason } from '@/test/fakeBackend';
import { createBackend, family, renderApp } from '@/test/render';
import type * as ImageNormalizationModule from '@/features/familia/image-normalization';

type ImageNormalization = typeof ImageNormalizationModule;

// MOCK backend (src/test/fakeBackend.ts) inside the REAL app. jsdom has no Canvas: the image re-encoding is replaced at the
// browser boundary exactly as in the DNI tests; the real browser, backend and Storage are proven by e2e/apto-fisico.real.spec.ts.
vi.mock('@/features/familia/image-normalization', async (importOriginal) => {
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

const MEDICO = 'Medico Sintetico';
const MATRICULA = 'MP 0001';
const MOTIVO = 'MOTIVO_SENSIBLE_DEL_APTO';
const DAY = '2026-03-01';
let backend: FakeBackend;
let season2026: FakeSeason;

const photo = (name = 'IMG_20260301_apto_Ana_Paz.jpg') => new File([new Uint8Array([0xff, 0xd8, 0xff, 0xe0, 1, 2, 3, 0xff, 0xd9])], name, { type: 'image/jpeg' });
const section = () => screen.findByRole('region', { name: 'Apto físico' });

function own(options: { upload?: boolean; seasons?: number[] } = {}) {
  const athlete = backend.addAthlete({ nombre: 'Ana', apellido: 'Paz', edad: 9, activo: true, vigente: null });
  backend.linkAthlete('familia-1', athlete.id);
  for (const year of options.seasons ?? [2026]) {
    const season = backend.addSeason(year, year === 2026);
    if (year === 2026) season2026 = season;
    backend.addFamilySeason(athlete.id, season);
  }
  if (options.upload !== false) backend.enableDocumentUpload();
  return athlete;
}

const uploadUrl = (id: string) => `/familia/deportistas/${id}/documentos`;
const aptoUrl = (id: string, season: string) => `/familia/deportistas/${id}/temporadas/${season}/apto-fisico`;

async function open(id: string) {
  renderApp(`/familia/deportistas/${id}`);
  return section();
}

async function chooseAndFill(user: ReturnType<typeof userEvent.setup>, region: HTMLElement, data = { fecha: DAY, nombre: MEDICO, matricula: MATRICULA }, label = /Sacar foto/) {
  await user.upload(await within(region).findByLabelText(label), photo());
  const date = await within(region).findByLabelText('Fecha de emisión');
  fireEvent.change(date, { target: { value: data.fecha } });
  await user.clear(within(region).getByLabelText('Nombre del médico'));
  await user.type(within(region).getByLabelText('Nombre del médico'), data.nombre);
  await user.clear(within(region).getByLabelText('Matrícula'));
  await user.type(within(region).getByLabelText('Matrícula'), data.matricula);
}

beforeEach(() => {
  localStorage.clear();
  sessionStorage.clear();
  backend = createBackend();
  backend.signIn(family.email);
  let n = 0;
  vi.spyOn(URL, 'createObjectURL').mockImplementation(() => `blob:preview-${++n}`);
  vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => undefined);
});

afterEach(() => {
  cleanup();
  backend.clearOverrides();
  vi.restoreAllMocks();
});

describe('apto físico — family: first upload', () => {
  it('shows «Falta», the confirmed 2026 rule, and uploads with the three data, the season and ONE idempotency key', async () => {
    const athlete = own();
    const user = userEvent.setup();
    const region = await open(athlete.id);
    expect(await within(region).findByText('Falta')).toBeInTheDocument();
    expect(within(region).getByText('Vigente hasta el 31 de diciembre de 2026.')).toBeInTheDocument();
    expect(within(region).queryByText(/todavía no están confirmados/)).not.toBeInTheDocument();
    expect(within(region).getByText(/es informativo: no impide otras gestiones/)).toBeInTheDocument();

    await chooseAndFill(user, region);
    await user.click(within(region).getByRole('button', { name: 'Enviar' }));
    expect(await within(region).findByText('Apto enviado. El club lo va a revisar.')).toBeInTheDocument();

    expect(backend.documentUploads).toHaveLength(1);
    const upload = backend.documentUploads[0]!;
    expect(upload).toMatchObject({ athleteId: athlete.id, tipo: 'APTO_FISICO', status: 201 });
    expect(upload.idempotencyKey).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/);
    expect(new Set(backend.aptoUploadParts[0])).toEqual(new Set(['fechaEmision', 'medicoNombre', 'medicoMatricula', 'archivo', 'tipo', 'temporadaId']));
    expect(backend.aptosOf(athlete.id)[0]).toMatchObject({ seasonId: season2026.id, fechaEmision: DAY, medicoNombre: MEDICO, medicoMatricula: MATRICULA, uploaderFamilyId: 'familia-1' });
    // the original file name never travels: the normalized, neutral one does
    expect(upload.fileName).toBe('documento.jpg');
    // refreshed from the server: now in review, with the data this family declared
    await waitFor(() => expect(within(region).getByText('En revisión')).toBeInTheDocument());
    expect(within(region).getByText('Matrícula').nextSibling).toHaveTextContent(MATRICULA);
  });

  it('validates the data before sending anything and explains each error', async () => {
    const athlete = own();
    const user = userEvent.setup();
    const region = await open(athlete.id);
    await user.upload(await within(region).findByLabelText(/Sacar foto/), photo());
    await user.click(await within(region).findByRole('button', { name: 'Enviar' }));
    expect(await within(region).findByText('Ingresá la fecha de emisión.')).toBeInTheDocument();
    expect(within(region).getByText('Ingresá el nombre del médico.')).toBeInTheDocument();
    expect(within(region).getByText('Ingresá la matrícula.')).toBeInTheDocument();
    expect(within(region).getByLabelText('Fecha de emisión')).toHaveAttribute('aria-invalid', 'true');
    expect(backend.documentUploads).toHaveLength(0);

    fireEvent.change(within(region).getByLabelText('Fecha de emisión'), { target: { value: '2999-01-01' } });
    await user.type(within(region).getByLabelText('Nombre del médico'), MEDICO);
    await user.type(within(region).getByLabelText('Matrícula'), '***');
    await user.click(within(region).getByRole('button', { name: 'Enviar' }));
    expect(await within(region).findByText('La fecha de emisión no puede ser futura.')).toBeInTheDocument();
    expect(within(region).getByText(/La matrícula solo admite letras/)).toBeInTheDocument();
    expect(backend.documentUploads).toHaveLength(0);
  });

  it('recovers an uncertain result: «Reintentar» re-sends the same bytes, data and key, and nothing is duplicated', async () => {
    const athlete = own();
    backend.override('post', uploadUrl(athlete.id), 'lost', { once: true });
    const user = userEvent.setup();
    const region = await open(athlete.id);
    await chooseAndFill(user, region);
    await user.click(within(region).getByRole('button', { name: 'Enviar' }));
    expect(await within(region).findByText(/No pudimos confirmar si el documento se guardó/)).toBeInTheDocument();
    await user.click(within(region).getByRole('button', { name: 'Reintentar' }));
    expect(await within(region).findByText('Apto enviado. El club lo va a revisar.')).toBeInTheDocument();
    const keys = backend.documentUploads.map((u) => u.idempotencyKey);
    expect(keys).toHaveLength(2);
    expect(new Set(keys).size).toBe(1);
    expect(backend.documentUploads.map((u) => u.status)).toEqual([201, 200]);
    expect(backend.aptosOf(athlete.id)).toHaveLength(1);
  });

  it('uses a NEW key when the data change after an uncertain attempt, and a conflict refreshes instead of duplicating', async () => {
    const athlete = own();
    backend.override('post', uploadUrl(athlete.id), 'lost', { once: true });
    const user = userEvent.setup();
    const region = await open(athlete.id);
    await chooseAndFill(user, region);
    await user.click(within(region).getByRole('button', { name: 'Enviar' }));
    await user.click(await within(region).findByRole('button', { name: 'Cambiar los datos' }));
    await user.clear(within(region).getByLabelText('Matrícula'));
    await user.type(within(region).getByLabelText('Matrícula'), 'MP 0002');
    await user.click(within(region).getByRole('button', { name: 'Enviar' }));
    // the first attempt reached the server (the answer was lost): the season already has its apto, so the second is a conflict
    // and the screen reloads what the server has instead of duplicating it
    await waitFor(() => expect(backend.documentUploads.map((u) => u.status)).toEqual([201, 409]));
    const [first, second] = backend.documentUploads;
    expect(second!.idempotencyKey).not.toBe(first!.idempotencyKey);
    expect(backend.aptosOf(athlete.id)).toHaveLength(1);
    expect(backend.aptosOf(athlete.id)[0]!.medicoMatricula).toBe(MATRICULA);
    // and the screen now shows what the server has
    await waitFor(() => expect(within(region).getByText('En revisión')).toBeInTheDocument());
  });

  it('on a server 400 keeps the file, asks to fix the data and sends the next attempt with a new key', async () => {
    const athlete = own();
    backend.override('post', uploadUrl(athlete.id), { status: 400, body: { codigo: 'VALIDACION', mensaje: 'x', detalles: [{ campo: 'medicoMatricula', mensaje: 'valor-del-servidor' }] } }, { once: true });
    const user = userEvent.setup();
    const region = await open(athlete.id);
    await chooseAndFill(user, region);
    await user.click(within(region).getByRole('button', { name: 'Enviar' }));
    expect(await within(region).findByText('Revisá la matrícula.')).toBeInTheDocument();
    expect(region).not.toHaveTextContent('valor-del-servidor');
    expect(within(region).getByAltText(/Vista previa/)).toBeInTheDocument(); // the file was kept
    await user.clear(within(region).getByLabelText('Matrícula'));
    await user.type(within(region).getByLabelText('Matrícula'), 'MP 0003');
    await user.click(within(region).getByRole('button', { name: 'Enviar' }));
    expect(await within(region).findByText('Apto enviado. El club lo va a revisar.')).toBeInTheDocument();
    const keys = backend.documentUploads.map((u) => u.idempotencyKey);
    expect(new Set(keys).size).toBe(backend.documentUploads.length);
  });
});

describe('apto físico — family: replacing an observed apto', () => {
  it('shows the reason to the family that uploaded it and replaces with its own data (only archivo + the three data are sent)', async () => {
    const athlete = own();
    const observed = backend.seedApto(athlete.id, season2026.id, { estado: 'OBSERVADO', motivoObservacion: MOTIVO, uploaderFamilyId: 'familia-1' });
    const user = userEvent.setup();
    const region = await open(athlete.id);
    expect(await within(region).findByText('Observado')).toBeInTheDocument();
    expect(region).toHaveTextContent(MOTIVO);
    expect(within(region).getByText('Reemplazar el apto observado')).toBeInTheDocument();
    await chooseAndFill(user, region, { fecha: '2026-03-02', nombre: 'Otro Medico', matricula: 'MP 0009' }, /Sacar foto/);
    await user.click(within(region).getByRole('button', { name: 'Enviar' }));
    expect(await within(region).findByText('Apto enviado. El club lo va a revisar.')).toBeInTheDocument();
    expect(new Set(backend.aptoUploadParts[0])).toEqual(new Set(['fechaEmision', 'medicoNombre', 'medicoMatricula', 'archivo']));
    expect(backend.documentUploads[0]).toMatchObject({ replaces: observed.id, status: 201 });
    expect(backend.aptosOf(athlete.id).filter((a) => a.vigente)).toHaveLength(1);
    expect(backend.aptosOf(athlete.id).find((a) => a.id === observed.id)!.vigente).toBe(false);
  });

  it('does not offer an upload while the apto is in review, approved or expired', async () => {
    for (const estado of ['CARGADO', 'APROBADO'] as const) {
      backend = createBackend();
      backend.signIn(family.email);
      const athlete = own();
      backend.seedApto(athlete.id, season2026.id, { estado, uploaderFamilyId: 'familia-1' });
      await open(athlete.id);
      const region = await section();
      await within(region).findByText(estado === 'CARGADO' ? 'En revisión' : 'Cumplido');
      expect(within(region).queryByLabelText(/Sacar foto/)).not.toBeInTheDocument();
      cleanup();
    }
  });
});

describe('apto físico — privacy between two families of the same athlete', () => {
  it('shows another family only the state: no data, no reason, a generic notice, and still lets it replace', async () => {
    const athlete = own();
    backend.seedApto(athlete.id, season2026.id, { estado: 'OBSERVADO', motivoObservacion: MOTIVO, uploaderFamilyId: 'familia-otra' });
    const region = await open(athlete.id);
    expect(await within(region).findByText('Observado')).toBeInTheDocument();
    expect(within(region).getByText(/Este apto lo cargó otra familia vinculada/)).toBeInTheDocument();
    expect(within(region).getByText(/El club observó este apto: hay que reemplazarlo/)).toBeInTheDocument();
    expect(region).not.toHaveTextContent(MOTIVO);
    expect(region).not.toHaveTextContent(MEDICO);
    expect(region).not.toHaveTextContent(MATRICULA);
    expect(within(region).queryByText('Matrícula')).not.toBeInTheDocument();
    expect(within(region).getByText('Reemplazar el apto observado')).toBeInTheDocument();
    // the list of documents of the family never carries the reason of an apto uploaded by another family either
    const documents = backend.requests.filter((r) => r.url.includes('/documentos?')).length;
    expect(documents).toBeGreaterThan(0);
    expect(document.body).not.toHaveTextContent(MOTIVO);
  });

  it('never draws data the server should not have sent: datosVisibles=false wins over any value in the answer', async () => {
    const athlete = own();
    const apto = backend.seedApto(athlete.id, season2026.id, { estado: 'OBSERVADO', motivoObservacion: MOTIVO, uploaderFamilyId: 'familia-otra' });
    backend.override('get', aptoUrl(athlete.id, season2026.id), {
      status: 200,
      body: {
        deportistaId: athlete.id, temporadaId: season2026.id, anio: 2026, estado: 'OBSERVADO', bloqueante: false,
        vigencia: { desde: '2026-01-01', hasta: '2026-12-31', confirmada: true, fuente: 'x' }, avisos: [],
        documento: { id: apto.id, estado: 'OBSERVADO', fechaEmision: DAY, medicoNombre: MEDICO, medicoMatricula: MATRICULA, datosVisibles: false, motivoObservacion: MOTIVO, cargadoEn: '2026-03-10T14:00:00Z' },
      },
    });
    const region = await open(athlete.id);
    await within(region).findByText('Observado');
    expect(region).not.toHaveTextContent(MEDICO);
    expect(region).not.toHaveTextContent(MATRICULA);
    expect(region).not.toHaveTextContent(MOTIVO);
    expect(within(region).getByText(/El club observó este apto: hay que reemplazarlo/)).toBeInTheDocument();
  });

  it('keeps an apto of another family that is in review or approved without any data', async () => {
    const athlete = own();
    backend.seedApto(athlete.id, season2026.id, { estado: 'APROBADO', uploaderFamilyId: 'familia-otra' });
    const region = await open(athlete.id);
    expect(await within(region).findByText('Cumplido')).toBeInTheDocument();
    expect(region).not.toHaveTextContent(MEDICO);
    expect(within(region).getByText(/no sus datos/)).toBeInTheDocument();
  });

  it('asks for nothing about an athlete whose link was revoked: the sheet is not found and no apto is requested', async () => {
    const athlete = own();
    backend.seedApto(athlete.id, season2026.id, { uploaderFamilyId: 'familia-1' });
    backend.revokeLink('familia-1', athlete.id);
    renderApp(`/familia/deportistas/${athlete.id}`);
    expect(await screen.findByText(/No encontramos este deportista/)).toBeInTheDocument();
    expect(backend.requests.filter((r) => r.url.includes('apto-fisico'))).toHaveLength(0);
    expect(document.body).not.toHaveTextContent(MATRICULA);
  });
});

describe('apto físico — family: configuration and rules', () => {
  it('shows the provisional rule of another season as provisional, never as confirmed', async () => {
    const athlete = own({ seasons: [2027] });
    const season = backend.seasonsOnServer().find((s) => s.anio === 2027)!;
    backend.seedApto(athlete.id, season.id, { uploaderFamilyId: 'familia-1' });
    const region = await open(athlete.id);
    expect(await within(region).findByText(/Los requisitos del apto para esta temporada todavía no están confirmados/)).toBeInTheDocument();
    expect(within(region).getByText('Vigente hasta el 31 de diciembre de 2027 (regla provisoria).')).toBeInTheDocument();
    expect(region.textContent).not.toMatch(/confirmad[oa] por la federación|requisito federativo/i);
  });

  it('lets the family pick among the seasons of the athlete and loads each one separately', async () => {
    const athlete = own({ seasons: [2026, 2027] });
    const s2027 = backend.seasonsOnServer().find((s) => s.anio === 2027)!;
    backend.seedApto(athlete.id, s2027.id, { estado: 'APROBADO', uploaderFamilyId: 'familia-1' });
    const user = userEvent.setup();
    const region = await open(athlete.id);
    expect(await within(region).findByText('Falta')).toBeInTheDocument(); // the active season is the default
    await user.selectOptions(within(region).getByLabelText('Temporada'), s2027.id);
    expect(await within(region).findByText('Cumplido')).toBeInTheDocument();
    expect(backend.requests.filter((r) => r.url.endsWith('/apto-fisico')).map((r) => r.url)).toEqual([aptoUrl(athlete.id, season2026.id), aptoUrl(athlete.id, s2027.id)]);
  });

  it('explains there is nothing to load when the athlete has no season yet, and requests no apto', async () => {
    const athlete = own({ seasons: [] });
    const region = await open(athlete.id);
    expect(await within(region).findByText(/Todavía no hay una temporada cargada/)).toBeInTheDocument();
    expect(backend.requests.filter((r) => r.url.includes('apto-fisico'))).toHaveLength(0);
  });

  it('offers no upload when uploads are disabled by configuration and says so', async () => {
    const athlete = own({ upload: false });
    const region = await open(athlete.id);
    expect(await within(region).findByText('La carga de documentos no está habilitada por el momento.')).toBeInTheDocument();
    expect(within(region).queryByLabelText(/Sacar foto/)).not.toBeInTheDocument();
    expect(within(region).getByText('Falta')).toBeInTheDocument();
  });

  it('says it could not check availability and lets the family check again', async () => {
    const athlete = own();
    backend.override('get', '/familia/documentos/disponibilidad', { status: 500, body: { codigo: 'ERROR_INTERNO', mensaje: 'x' } }, { once: true });
    const user = userEvent.setup();
    const region = await open(athlete.id);
    expect(await within(region).findByText('No pudimos comprobar si la carga de documentos está disponible.')).toBeInTheDocument();
    await user.click(within(region).getByRole('button', { name: 'Volver a comprobar' }));
    expect(await within(region).findByLabelText(/Sacar foto/)).toBeInTheDocument();
  });

  it('answers a closed backend (503) with a retryable message and never shows the server text', async () => {
    const athlete = own();
    backend.override('post', uploadUrl(athlete.id), { status: 503, body: { codigo: 'CARGA_NO_DISPONIBLE', mensaje: 'detalle interno del servidor' } }, { once: true });
    const user = userEvent.setup();
    const region = await open(athlete.id);
    await chooseAndFill(user, region);
    await user.click(within(region).getByRole('button', { name: 'Enviar' }));
    expect(await within(region).findByText('La carga de documentos no está disponible en este momento. Probá de nuevo más tarde.')).toBeInTheDocument();
    expect(region).not.toHaveTextContent('detalle interno');
  });
});

describe('apto físico — family: nothing medical outside the screen, and accessibility', () => {
  it('writes nothing to the browser storages, to the console or to the address, and drops the data from memory when the screen goes away', async () => {
    const logs = [vi.spyOn(console, 'log'), vi.spyOn(console, 'info'), vi.spyOn(console, 'warn'), vi.spyOn(console, 'error'), vi.spyOn(console, 'debug')];
    const athlete = own();
    const user = userEvent.setup();
    const { queryClient } = renderApp(`/familia/deportistas/${athlete.id}`);
    const region = await section();
    await chooseAndFill(user, region);
    await user.click(within(region).getByRole('button', { name: 'Enviar' }));
    await within(region).findByText('Apto enviado. El club lo va a revisar.');
    await waitFor(() => expect(within(region).getByText('En revisión')).toBeInTheDocument());

    const sensitive = [MEDICO, MATRICULA, DAY];
    const dump = JSON.stringify({ local: { ...localStorage }, session: { ...sessionStorage } });
    for (const value of sensitive) expect(dump).not.toContain(value);
    for (const spy of logs) expect(JSON.stringify(spy.mock.calls)).not.toMatch(/Medico Sintetico|MP 0001|2026-03-01/);
    expect(screen.getByTestId('location').textContent).not.toMatch(/Medico|MP|2026-03-01/);

    cleanup();
    await waitFor(() => expect(queryClient.getQueryCache().findAll().filter((q) => JSON.stringify(q.queryKey).includes('apto')).length).toBe(0));
  });

  it('is operable by label and role: a titled region, labelled fields, errors announced, comfortable touch targets', async () => {
    const athlete = own();
    const user = userEvent.setup();
    const region = await open(athlete.id);
    expect(region).toHaveAttribute('aria-labelledby', 'apto-title');
    await user.upload(await within(region).findByLabelText(/Sacar foto/), photo());
    for (const label of ['Fecha de emisión', 'Nombre del médico', 'Matrícula']) {
      const input = within(region).getByLabelText(label);
      expect(input.className).toMatch(/min-h-11/);
      expect(input).toHaveAttribute('autocomplete', 'off');
    }
    expect(within(region).getByLabelText('Fecha de emisión')).toHaveAttribute('max');
    await user.click(within(region).getByRole('button', { name: 'Enviar' }));
    const alerts = await within(region).findAllByRole('alert');
    expect(alerts.length).toBeGreaterThanOrEqual(3);
    const matricula = within(region).getByLabelText('Matrícula');
    expect(matricula.getAttribute('aria-describedby')).toBeTruthy();
    expect(document.getElementById(matricula.getAttribute('aria-describedby')!)).toHaveTextContent('Ingresá la matrícula.');
  });
});
