import { act, cleanup, fireEvent, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { FakeAdminAthlete, FakeBackend, FakeSeason } from '@/test/fakeBackend';
import { createBackend, renderApp } from '@/test/render';
import { ApiError } from '@/lib/api-error';
import { KEEPS_IN_REVIEW, WITHDRAWS_APPROVAL } from './AptoCorrectionDialog';
import { correctionFailure } from './use-apto-correction';

// MOCK backend (src/test/fakeBackend.ts mirrors the apto routes of the backend at 3e51372). The real backend, PostgreSQL and the
// audited download are proven by e2e/documentos-apto-fisico.real.spec.ts.
const MEDICO = 'Medico Sintetico';
const MATRICULA = 'MP 0001';
const MOTIVO = 'Motivo sintetico del club';
let backend: FakeBackend;
let athlete: FakeAdminAthlete;
let season: FakeSeason;

beforeEach(() => {
  localStorage.clear();
  sessionStorage.clear();
  backend = createBackend();
  athlete = backend.addAdminAthlete({ nombre: 'Lucía', apellido: 'Sintética', dni: '30111322' });
  season = backend.addSeason(2026, true);
  let n = 0;
  Object.defineProperty(URL, 'createObjectURL', { configurable: true, writable: true, value: vi.fn(() => `blob:http://app.test/${++n}`) });
  Object.defineProperty(URL, 'revokeObjectURL', { configurable: true, writable: true, value: vi.fn() });
});

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

const section = () => screen.findByRole('region', { name: 'Apto físico' });
async function openDetail() {
  backend.signIn('marcos@example.com');
  const result = renderApp(`/admin/deportistas/${athlete.id}`);
  await screen.findByRole('heading', { level: 1, name: 'Lucía Sintética' });
  return { ...result, region: await section() };
}
const reviewRequests = () => backend.requests.filter((r) => /\/revision$/.test(r.url));
const correctionRequests = () => backend.requests.filter((r) => /\/datos-apto$/.test(r.url));

describe('apto físico — ADMIN: consulta', () => {
  it('shows the state, the validity and the data the family declared, with the actions that apply', async () => {
    backend.seedApto(athlete.id, season.id, { estado: 'CARGADO', uploaderFamilyId: 'familia-1' });
    const { region } = await openDetail();
    expect(await within(region).findByText('En revisión')).toBeInTheDocument();
    expect(within(region).getByText('Vigente hasta el 31 de diciembre de 2026.')).toBeInTheDocument();
    expect(within(region).getByText('Matrícula').nextSibling).toHaveTextContent(MATRICULA);
    expect(within(region).getByText('Médico').nextSibling).toHaveTextContent(MEDICO);
    expect(within(region).getByText('Fecha de emisión').nextSibling).toHaveTextContent('1 de marzo de 2026');
    for (const name of ['Abrir documento: Apto físico', 'Aprobar documento: Apto físico', 'Observar documento: Apto físico', 'Corregir los datos del apto físico']) {
      expect(within(region).getByRole('button', { name })).toBeEnabled();
    }
    expect(backend.requests.filter((r) => r.url.endsWith('/apto-fisico')).map((r) => r.url)).toEqual([`/admin/deportistas/${athlete.id}/temporadas/${season.id}/apto-fisico`]);
  });

  it('says «Falta» with no actions when the season has no apto, and the provisional rule of another season as provisional', async () => {
    const next = backend.addSeason(2027, false);
    backend.seedApto(athlete.id, next.id, { uploaderFamilyId: 'familia-1' });
    const user = userEvent.setup();
    const { region } = await openDetail();
    expect(await within(region).findByText('Falta')).toBeInTheDocument();
    expect(within(region).queryByRole('button', { name: /Aprobar|Observar|Abrir|Corregir/ })).not.toBeInTheDocument();
    await user.selectOptions(within(region).getByLabelText('Temporada'), next.id);
    expect(await within(region).findByText(/todavía no están confirmados/)).toBeInTheDocument();
    expect(within(region).getByText(/regla provisoria/)).toBeInTheDocument();
  });

  it('shows ADMIN the data and the reason even when the apto was uploaded by any family', async () => {
    backend.seedApto(athlete.id, season.id, { estado: 'OBSERVADO', motivoObservacion: MOTIVO, uploaderFamilyId: 'familia-otra' });
    const { region } = await openDetail();
    expect(await within(region).findByText('Observado')).toBeInTheDocument();
    expect(region).toHaveTextContent(MATRICULA);
    expect(region).toHaveTextContent(MOTIVO);
    expect(within(region).queryByRole('button', { name: /Corregir/ })).not.toBeInTheDocument(); // an observed apto is replaced, not corrected
    expect(within(region).queryByRole('button', { name: /Aprobar|Observar/ })).not.toBeInTheDocument();
  });

  it('opens the file through the audited route and shows it in the viewer', async () => {
    const apto = backend.seedApto(athlete.id, season.id, { uploaderFamilyId: 'familia-1' });
    const user = userEvent.setup();
    const { region } = await openDetail();
    await user.click(await within(region).findByRole('button', { name: 'Abrir documento: Apto físico' }));
    expect(await within(region).findByRole('img', { name: 'Documento: Apto físico' })).toBeInTheDocument();
    expect(backend.documentDownloads).toEqual([{ athleteId: athlete.id, documentId: apto.id, status: 200 }]);
  });

  it('does not repeat the apto in «Documentos»', async () => {
    backend.seedApto(athlete.id, season.id, { uploaderFamilyId: 'familia-1' });
    await openDetail();
    const documents = await screen.findByRole('region', { name: 'Documentos' });
    expect(within(documents).queryByText('Apto físico')).not.toBeInTheDocument();
  });
});

describe('apto físico — ADMIN: revisión', () => {
  it('approves with the revision read as a decimal string and shows the new state', async () => {
    const apto = backend.seedApto(athlete.id, season.id, { uploaderFamilyId: 'familia-1' });
    const user = userEvent.setup();
    const { region } = await openDetail();
    await user.click(await within(region).findByRole('button', { name: 'Aprobar documento: Apto físico' }));
    await user.click(await screen.findByRole('button', { name: 'Confirmar aprobación' }));
    expect(await within(region).findByText('Documento aprobado.')).toBeInTheDocument();
    expect(reviewRequests()).toHaveLength(1);
    expect(backend.aptosOf(athlete.id)[0]).toMatchObject({ id: apto.id, estado: 'APROBADO', version: '1' });
    await waitFor(() => expect(within(region).getByText('Cumplido')).toBeInTheDocument());
  });

  it('observes with a mandatory reason that the uploading family can then read', async () => {
    backend.seedApto(athlete.id, season.id, { uploaderFamilyId: 'familia-1' });
    const user = userEvent.setup();
    const { region } = await openDetail();
    await user.click(await within(region).findByRole('button', { name: 'Observar documento: Apto físico' }));
    await user.click(await screen.findByRole('button', { name: 'Confirmar observación' }));
    expect(await screen.findByText('Ingresá un motivo de entre 1 y 500 caracteres.')).toBeInTheDocument();
    expect(reviewRequests()).toHaveLength(0);
    await user.type(screen.getByLabelText('Motivo de la observación'), MOTIVO);
    await user.click(screen.getByRole('button', { name: 'Confirmar observación' }));
    expect(await within(region).findByText('Documento observado.')).toBeInTheDocument();
    await waitFor(() => expect(within(region).getByText('Observado')).toBeInTheDocument());
    expect(region).toHaveTextContent(MOTIVO);
  });
});

describe('apto físico — ADMIN: corrección de datos', () => {
  const openCorrection = async (user: ReturnType<typeof userEvent.setup>, region: HTMLElement) => {
    await user.click(await within(region).findByRole('button', { name: 'Corregir los datos del apto físico' }));
    return screen.findByRole('dialog', { name: 'Corregir los datos del apto' });
  };

  it('says before confirming that correcting an APPROVED apto withdraws its approval, then does it with the optimistic revision', async () => {
    const apto = backend.seedApto(athlete.id, season.id, { estado: 'APROBADO', version: '4', uploaderFamilyId: 'familia-1' });
    const user = userEvent.setup();
    const { region } = await openDetail();
    const dialog = await openCorrection(user, region);
    expect(within(dialog).getByText(WITHDRAWS_APPROVAL)).toBeInTheDocument();
    // prefilled, and nothing to save until something changes
    expect(within(dialog).getByLabelText('Matrícula')).toHaveValue(MATRICULA);
    expect(within(dialog).getByRole('button', { name: 'Corregir y retirar la aprobación' })).toBeDisabled();
    await user.clear(within(dialog).getByLabelText('Matrícula'));
    await user.type(within(dialog).getByLabelText('Matrícula'), 'MP 0002');
    await user.click(within(dialog).getByRole('button', { name: 'Corregir y retirar la aprobación' }));
    expect(await within(region).findByText('Datos corregidos. El apto vuelve a revisión.')).toBeInTheDocument();
    expect(correctionRequests()).toHaveLength(1);
    expect(backend.aptosOf(athlete.id)[0]).toMatchObject({ id: apto.id, estado: 'CARGADO', medicoMatricula: 'MP 0002', version: '5' });
    await waitFor(() => expect(within(region).getByText('En revisión')).toBeInTheDocument());
    expect(within(region).getByRole('button', { name: 'Aprobar documento: Apto físico' })).toBeEnabled(); // it can be approved again
    expect(region).toHaveTextContent('MP 0002');
  });

  it('tells that a correction of an apto in review keeps it in review', async () => {
    backend.seedApto(athlete.id, season.id, { estado: 'CARGADO', uploaderFamilyId: 'familia-1' });
    const user = userEvent.setup();
    const { region } = await openDetail();
    const dialog = await openCorrection(user, region);
    expect(within(dialog).getByText(KEEPS_IN_REVIEW)).toBeInTheDocument();
    expect(within(dialog).queryByText(WITHDRAWS_APPROVAL)).not.toBeInTheDocument();
  });

  it('validates the data in the dialog and sends nothing while they are invalid', async () => {
    backend.seedApto(athlete.id, season.id, { uploaderFamilyId: 'familia-1' });
    const user = userEvent.setup();
    const { region } = await openDetail();
    const dialog = await openCorrection(user, region);
    fireEvent.change(within(dialog).getByLabelText('Fecha de emisión'), { target: { value: '2999-01-01' } });
    await user.clear(within(dialog).getByLabelText('Nombre del médico'));
    await user.click(within(dialog).getByRole('button', { name: 'Guardar corrección' }));
    expect(await within(dialog).findByText('La fecha de emisión no puede ser futura.')).toBeInTheDocument();
    expect(within(dialog).getByText('Ingresá el nombre del médico.')).toBeInTheDocument();
    expect(correctionRequests()).toHaveLength(0);
  });

  it('reports a stale revision, reloads the apto and keeps nothing of what was typed', async () => {
    const apto = backend.seedApto(athlete.id, season.id, { estado: 'APROBADO', uploaderFamilyId: 'familia-1' });
    const user = userEvent.setup();
    const { region } = await openDetail();
    const dialog = await openCorrection(user, region);
    backend.changeAptoBehindTheScenes(apto.id, { medicoMatricula: 'MP DE OTRO ADMIN' });
    await user.clear(within(dialog).getByLabelText('Matrícula'));
    await user.type(within(dialog).getByLabelText('Matrícula'), 'MP 0777');
    await user.click(within(dialog).getByRole('button', { name: 'Corregir y retirar la aprobación' }));
    expect(await within(region).findByText(/El apto cambió mientras lo editabas/)).toBeInTheDocument();
    expect(backend.aptosOf(athlete.id)[0]!.medicoMatricula).toBe('MP DE OTRO ADMIN');
    await waitFor(() => expect(region).toHaveTextContent('MP DE OTRO ADMIN'));
    expect(region).not.toHaveTextContent('MP 0777');
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('explains that an observed apto cannot be corrected when it became observed meanwhile', async () => {
    const apto = backend.seedApto(athlete.id, season.id, { estado: 'CARGADO', uploaderFamilyId: 'familia-1' });
    const user = userEvent.setup();
    const { region } = await openDetail();
    const dialog = await openCorrection(user, region);
    act(() => {
      backend.changeAptoBehindTheScenes(apto.id, { estado: 'OBSERVADO', motivoObservacion: MOTIVO });
      backend.changeAptoBehindTheScenes(apto.id, {});
    });
    // the revision moved twice: the stale-revision answer comes first, which is also a refresh of the screen
    await user.clear(within(dialog).getByLabelText('Matrícula'));
    await user.type(within(dialog).getByLabelText('Matrícula'), 'MP 0555');
    await user.click(within(dialog).getByRole('button', { name: 'Guardar corrección' }));
    expect(await within(region).findByText(/El apto cambió mientras lo editabas|Un apto observado no se corrige/)).toBeInTheDocument();
    await waitFor(() => expect(within(region).queryByRole('button', { name: /Corregir/ })).not.toBeInTheDocument());
  });

  it('keeps the typed data out of the browser storages, the console and the address', async () => {
    const logs = [vi.spyOn(console, 'log'), vi.spyOn(console, 'info'), vi.spyOn(console, 'warn'), vi.spyOn(console, 'error')];
    backend.seedApto(athlete.id, season.id, { estado: 'APROBADO', uploaderFamilyId: 'familia-1' });
    const user = userEvent.setup();
    const { region } = await openDetail();
    const dialog = await openCorrection(user, region);
    await user.clear(within(dialog).getByLabelText('Matrícula'));
    await user.type(within(dialog).getByLabelText('Matrícula'), 'MP 0888');
    await user.click(within(dialog).getByRole('button', { name: 'Corregir y retirar la aprobación' }));
    await within(region).findByText('Datos corregidos. El apto vuelve a revisión.');
    const dump = JSON.stringify({ local: { ...localStorage }, session: { ...sessionStorage } });
    expect(dump).not.toMatch(/MP 0888|Medico Sintetico|2026-03-01/);
    for (const spy of logs) expect(JSON.stringify(spy.mock.calls)).not.toMatch(/MP 0888|Medico Sintetico/);
    expect(screen.getByTestId('location').textContent).not.toMatch(/MP|Medico/);
  });
});

describe('apto físico — ADMIN: bandeja de pendientes', () => {
  it('lists an apto waiting for review with its readable type and leads to the ficha where the apto is reviewed', async () => {
    const apto = backend.seedApto(athlete.id, season.id, { uploaderFamilyId: 'familia-1' });
    backend.seedPendingInbox([{ id: apto.id, tipo: 'APTO_FISICO', creadoEn: apto.cargadoEn, titularTipo: 'DEPORTISTA', titularId: athlete.id, titularNombre: 'Lucía Sintética', familiaId: null, reemplazo: false }]);
    backend.signIn('marcos@example.com');
    const user = userEvent.setup();
    renderApp('/admin/documentos/pendientes');
    const link = await screen.findByRole('link', { name: 'Revisar Apto físico de Lucía Sintética' });
    await user.click(link);
    const region = await screen.findByRole('region', { name: 'Apto físico' });
    expect(await within(region).findByRole('button', { name: 'Aprobar documento: Apto físico' })).toBeEnabled();
  });
});

describe('apto correction failures', () => {
  const api = (status: number, code: string) => ApiError.from({ isAxiosError: true, response: { status, data: { codigo: code } }, config: {} });
  it('maps each backend conflict to a fixed text and keeps the dialog open only to fix the data', () => {
    expect(correctionFailure(api(400, 'VALIDACION')).keepOpen).toBe(true);
    for (const code of ['EDICION_DESACTUALIZADA', 'APTO_NO_CORREGIBLE', 'DOCUMENTO_NO_VIGENTE', 'CONFLICTO_CONCURRENCIA', 'VERSION_DOCUMENTO_AGOTADA']) {
      const failure = correctionFailure(api(409, code));
      expect(failure.keepOpen).toBe(false);
      expect(failure.message).not.toMatch(/OPERACION|undefined/);
    }
    expect(correctionFailure(api(404, 'DOCUMENTO_NO_ENCONTRADO')).message).toMatch(/No encontramos/);
    expect(correctionFailure(new Error('x')).message).toMatch(/No se pudo confirmar/);
  });
});
