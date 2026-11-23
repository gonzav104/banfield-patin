import { cleanup, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { FakeBackend } from '@/test/fakeBackend';
import { createBackend, family, renderApp } from '@/test/render';
import type * as ImageNormalizationModule from './image-normalization';

type ImageNormalization = typeof ImageNormalizationModule;

// MOCK backend (src/test/fakeBackend.ts mirrors GET /api/familia/deportistas/{id}/checklist-documental, U20) inside the REAL app.
// The real pipeline and authorization are proven by the PostgreSQL tests and e2e/documentos-checklist.real.spec.ts.
vi.mock('./image-normalization', async (importOriginal) => {
  const real = await importOriginal<ImageNormalization>();
  return {
    ...real,
    prepareDocument: vi.fn(async (file: File) => {
      const blob = new Blob([await real.readBytes(file)], { type: 'image/jpeg' });
      return { ok: true as const, prepared: { kind: 'image' as const, blob, fileName: 'documento.jpg' as const, width: 2400, height: 1800, lowResolution: false } };
    }),
  };
});

let backend: FakeBackend;
const photo = () => new File([new Uint8Array([0xff, 0xd8, 0xff, 0xe0, 1, 2, 3, 0xff, 0xd9])], 'IMG.jpg', { type: 'image/jpeg' });
const checklistUrl = (id: string) => `/familia/deportistas/${id}/checklist-documental`;
const checklistGets = () => backend.requests.filter((r) => r.method === 'get' && r.url.endsWith('/checklist-documental'));
const checklist = () => screen.findByRole('region', { name: 'Checklist de documentación' });
const item = async (label: 'DNI – frente' | 'DNI – dorso') => {
  const rows = await within(await checklist()).findAllByRole('listitem');
  return rows.find((row) => row.textContent?.startsWith(label))!;
};

function own(over: Partial<Parameters<FakeBackend['addAthlete']>[0]> = {}) {
  const athlete = backend.addAthlete({ nombre: 'Ana', apellido: 'Paz', edad: 9, activo: true, vigente: null, ...over });
  backend.linkAthlete('familia-1', athlete.id);
  return athlete;
}

beforeEach(() => {
  backend = createBackend();
  backend.signIn(family.email);
  vi.spyOn(URL, 'createObjectURL').mockImplementation(() => 'blob:preview');
  vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => undefined);
});

afterEach(() => {
  cleanup();
  backend.clearOverrides();
});

describe('partial document checklist in the athlete sheet (U20)', () => {
  it('says it is partial, lists both DNI sides as «Falta» and offers to go to the existing document actions', async () => {
    const athlete = own();
    renderApp(`/familia/deportistas/${athlete.id}`);

    const region = await checklist();
    expect(within(region).getByText('Este checklist es parcial: solo revisa el DNI del deportista (frente y dorso). No indica si el deportista está listo para empadronarse.')).toBeInTheDocument();
    expect(await item('DNI – frente')).toHaveTextContent('Estado: Falta');
    expect(await item('DNI – dorso')).toHaveTextContent('Estado: Falta');
    expect(checklistGets().map((r) => r.url)).toEqual([checklistUrl(athlete.id)]);
    // No overall percentage and no registration state anywhere in it.
    expect(region.textContent).not.toMatch(/%|LISTO_PARA_PRESENTAR|EMPADRONADO|completo/i);

    await userEvent.click(within(region).getByRole('button', { name: 'Ir a los documentos' }));
    expect(screen.getByRole('region', { name: 'Documentos del deportista' })).toHaveFocus();
  });

  it('maps each state: approved, in review and observed (with its reason); only approved fulfils', async () => {
    const athlete = own();
    backend.seedDocument(athlete.id, { tipo: 'DNI_DEPORTISTA_FRENTE', estado: 'APROBADO' });
    backend.seedDocument(athlete.id, { tipo: 'DNI_DEPORTISTA_DORSO', estado: 'OBSERVADO', motivoObservacion: 'El dorso está cortado.' });
    renderApp(`/familia/deportistas/${athlete.id}`);

    expect(await item('DNI – frente')).toHaveTextContent('Estado: Aprobado');
    const back = await item('DNI – dorso');
    expect(back).toHaveTextContent('Estado: Observado');
    expect(back).toHaveTextContent('Motivo de la observación: El dorso está cortado.');
    expect(await item('DNI – frente')).not.toHaveTextContent('Motivo');
    cleanup();

    const second = own({ nombre: 'Eva' });
    backend.seedDocument(second.id, { tipo: 'DNI_DEPORTISTA_FRENTE', estado: 'CARGADO' });
    backend.seedDocument(second.id, { tipo: 'DNI_DEPORTISTA_DORSO', estado: 'CARGADO' });
    renderApp(`/familia/deportistas/${second.id}`);
    expect(await item('DNI – frente')).toHaveTextContent('Estado: En revisión');
    expect(await item('DNI – dorso')).toHaveTextContent('Estado: En revisión');
    // Nothing is left for the family to do: no shortcut to the upload actions.
    expect(within(await checklist()).queryByRole('button')).not.toBeInTheDocument();
  });

  it('refreshes after an upload: the new DNI side is «En revisión» without reloading', async () => {
    backend.enableDocumentUpload();
    const athlete = own();
    renderApp(`/familia/deportistas/${athlete.id}`);
    expect(await item('DNI – frente')).toHaveTextContent('Estado: Falta');

    const user = userEvent.setup();
    const slot = await within(await screen.findByRole('region', { name: 'Documentos del deportista' })).findByRole('article', { name: 'DNI – frente' });
    await user.upload(within(slot).getByLabelText('Sacar foto del DNI – frente'), photo());
    await user.click(await within(slot).findByRole('button', { name: 'Enviar' }));

    await waitFor(async () => expect(await item('DNI – frente')).toHaveTextContent('Estado: En revisión'));
    expect(await item('DNI – dorso')).toHaveTextContent('Estado: Falta');
  });

  it('a failed read says so, keeps the sheet and retries only when the family chooses', async () => {
    const athlete = own();
    backend.override('get', checklistUrl(athlete.id), { status: 500, body: { codigo: 'ERROR_INTERNO', mensaje: 'x' } });
    renderApp(`/familia/deportistas/${athlete.id}`);

    const region = await checklist();
    expect(await within(region).findByRole('alert')).toHaveTextContent('No se pudo cargar el checklist.');
    expect(screen.getByRole('heading', { level: 1, name: 'Ana Paz' })).toBeInTheDocument();
    expect(await screen.findByRole('region', { name: 'Documentos del deportista' })).toBeInTheDocument(); // the documents still work
    const before = checklistGets().length;
    backend.clearOverrides();
    await userEvent.click(within(region).getByRole('button', { name: 'Reintentar' }));
    expect(await item('DNI – frente')).toHaveTextContent('Estado: Falta');
    expect(checklistGets().length).toBe(before + 1);
  });

  it('a response that does not follow the contract is an error, never a half-drawn list', async () => {
    const athlete = own();
    backend.override('get', checklistUrl(athlete.id), {
      status: 200,
      body: { deportistaId: athlete.id, parcial: false, alcance: 'DNI_DEPORTISTA', requisitos: [{ tipo: 'DNI_DEPORTISTA_FRENTE', estado: 'LISTO_PARA_PRESENTAR' }] },
    });
    renderApp(`/familia/deportistas/${athlete.id}`);
    expect(await within(await checklist()).findByRole('alert')).toHaveTextContent('No se pudo cargar el checklist.');
    expect(screen.queryByText('Estado: Falta')).not.toBeInTheDocument();
  });

  it('an athlete that is not the family’s (or an id that is not a UUID) requests no checklist', async () => {
    renderApp('/familia/deportistas/00000000-0000-4000-8000-0000000000ff');
    expect(await screen.findByText(/No encontramos/)).toBeInTheDocument();
    cleanup();
    renderApp('/familia/deportistas/no-es-un-uuid');
    expect(await screen.findByText(/No encontramos/)).toBeInTheDocument();
    expect(checklistGets()).toHaveLength(0);
  });
});

describe('DNI line in the family dashboard (U20)', () => {
  it('shows one line per athlete of the preview, the most urgent state first, and nothing about the registration', async () => {
    const observed = own({ nombre: 'Ana' });
    backend.seedDocument(observed.id, { tipo: 'DNI_DEPORTISTA_FRENTE', estado: 'APROBADO' });
    backend.seedDocument(observed.id, { tipo: 'DNI_DEPORTISTA_DORSO', estado: 'OBSERVADO', motivoObservacion: 'Borroso.' });
    const missing = own({ nombre: 'Beto' });
    backend.seedDocument(missing.id, { tipo: 'DNI_DEPORTISTA_FRENTE', estado: 'CARGADO' });
    const approved = own({ nombre: 'Caro' });
    backend.seedDocument(approved.id, { tipo: 'DNI_DEPORTISTA_FRENTE', estado: 'APROBADO' });
    backend.seedDocument(approved.id, { tipo: 'DNI_DEPORTISTA_DORSO', estado: 'APROBADO' });
    renderApp('/familia');

    const card = (name: string) => screen.findByRole('link', { name: new RegExp(`^${name}`) });
    expect(await within(await card('Ana')).findByText('DNI del deportista (frente y dorso): observado')).toBeInTheDocument();
    expect(await within(await card('Beto')).findByText('DNI del deportista (frente y dorso): falta')).toBeInTheDocument();
    expect(await within(await card('Caro')).findByText('DNI del deportista (frente y dorso): aprobado')).toBeInTheDocument();
    expect(screen.getByRole('main').textContent).not.toMatch(/%|LISTO_PARA_PRESENTAR|EMPADRONADO|empadronarse/i);
    // The reason of an observation is for the sheet, not the dashboard.
    expect(screen.queryByText(/Borroso/)).not.toBeInTheDocument();
  });

  it('an «en revisión» DNI is not presented as pending for the family, and a failed line does not break the card', async () => {
    const reviewing = own({ nombre: 'Ana' });
    backend.seedDocument(reviewing.id, { tipo: 'DNI_DEPORTISTA_FRENTE', estado: 'CARGADO' });
    backend.seedDocument(reviewing.id, { tipo: 'DNI_DEPORTISTA_DORSO', estado: 'CARGADO' });
    const failing = own({ nombre: 'Beto' });
    backend.override('get', checklistUrl(failing.id), { status: 500, body: { codigo: 'ERROR_INTERNO', mensaje: 'x' } });
    renderApp('/familia');
    expect(await screen.findByText('DNI del deportista (frente y dorso): en revisión')).toBeInTheDocument();
    expect(await screen.findByText('Documentación no disponible por ahora.')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /^Beto/ })).toHaveAttribute('href', `/familia/deportistas/${failing.id}`);
  });

  it('the full athletes list has no DNI line and requests no checklist', async () => {
    own();
    renderApp('/familia/deportistas');
    await screen.findByRole('link', { name: /^Ana/ });
    expect(checklistGets()).toHaveLength(0);
  });
});
