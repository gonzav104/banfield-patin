import { cleanup, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import type { FakeAdminAthlete, FakeBackend } from '@/test/fakeBackend';
import { createBackend, renderApp } from '@/test/render';

// MOCK backend (src/test/fakeBackend.ts mirrors GET /api/admin/deportistas/{id}/checklist-documental, U20). Real-backend evidence
// lives in e2e/documentos-checklist.real.spec.ts.
let backend: FakeBackend;
let athlete: FakeAdminAthlete;

const checklistUrl = (id: string) => `/admin/deportistas/${id}/checklist-documental`;
const checklistGets = () => backend.requests.filter((r) => r.method === 'get' && r.url.endsWith('/checklist-documental'));
const checklist = () => screen.findByRole('region', { name: 'Checklist de documentación' });
const documents = () => screen.getByRole('region', { name: 'Documentos' });
const item = async (label: 'DNI – frente' | 'DNI – dorso') =>
  (await within(await checklist()).findAllByRole('listitem')).find((row) => row.textContent?.startsWith(label))!;

beforeEach(() => {
  localStorage.clear();
  sessionStorage.clear();
  backend = createBackend();
  athlete = backend.addAdminAthlete({ nombre: 'Lucía', apellido: 'Sintética', dni: '30111322' });
  backend.signIn('marcos@example.com');
});

afterEach(() => {
  cleanup();
  backend.clearOverrides();
});

async function openDetail() {
  renderApp(`/admin/deportistas/${athlete.id}`);
  await screen.findByRole('heading', { level: 1, name: 'Lucía Sintética' });
}

describe('partial document checklist in the administrative ficha (U20)', () => {
  it('shows the same partial checklist before «Documentos», says it is partial and requests only that route', async () => {
    await openDetail();
    const region = await checklist();
    expect(within(region).getByText('Este checklist es parcial: solo revisa el DNI del deportista (frente y dorso). No indica si el deportista está listo para empadronarse.')).toBeInTheDocument();
    expect(await item('DNI – frente')).toHaveTextContent('Estado: Falta');
    expect(await item('DNI – dorso')).toHaveTextContent('Estado: Falta');
    expect(checklistGets().map((r) => r.url)).toEqual([checklistUrl(athlete.id)]);
    expect(region.textContent).not.toMatch(/%|LISTO_PARA_PRESENTAR|EMPADRONADO|completo/i);
    // The checklist comes right before the documents (headings order) and does not duplicate the inbox.
    const headings = screen.getAllByRole('heading', { level: 2 }).map((h) => h.textContent);
    expect(headings.indexOf('Checklist de documentación')).toBe(headings.indexOf('Documentos') - 1);
    expect(screen.queryByText(/pendientes de revisión/i)).not.toBeInTheDocument();
  });

  it('maps every state with the observation reason, and fulfils only on «Aprobado»', async () => {
    backend.seedDocument(athlete.id, { tipo: 'DNI_DEPORTISTA_FRENTE', estado: 'CARGADO' });
    backend.seedDocument(athlete.id, { tipo: 'DNI_DEPORTISTA_DORSO', estado: 'OBSERVADO', motivoObservacion: 'El dorso está cortado.' });
    await openDetail();
    expect(await item('DNI – frente')).toHaveTextContent('Estado: En revisión');
    const back = await item('DNI – dorso');
    expect(back).toHaveTextContent('Estado: Observado');
    expect(back).toHaveTextContent('Motivo de la observación: El dorso está cortado.');
  });

  it('the shortcut moves the focus to the documents, where the review already is', async () => {
    await openDetail();
    await userEvent.click(within(await checklist()).getByRole('button', { name: 'Ir a los documentos' }));
    expect(documents()).toHaveFocus();
  });

  it('refreshes after a review: approving the DNI side turns the checklist into «Aprobado» without reloading', async () => {
    backend.seedDocument(athlete.id, { tipo: 'DNI_DEPORTISTA_FRENTE', estado: 'CARGADO' });
    await openDetail();
    expect(await item('DNI – frente')).toHaveTextContent('Estado: En revisión');

    const user = userEvent.setup();
    await user.click(await within(documents()).findByRole('button', { name: 'Aprobar documento: DNI – frente' }));
    await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Confirmar aprobación' }));

    await waitFor(async () => expect(await item('DNI – frente')).toHaveTextContent('Estado: Aprobado'));
    expect(await item('DNI – dorso')).toHaveTextContent('Estado: Falta');
  });

  it('a failed read says so, keeps the ficha and retries only when the administrator chooses', async () => {
    backend.override('get', checklistUrl(athlete.id), { status: 500, body: { codigo: 'ERROR_INTERNO', mensaje: 'x' } });
    await openDetail();
    const region = await checklist();
    expect(await within(region).findByRole('alert')).toHaveTextContent('No se pudo cargar el checklist.');
    expect(await within(documents()).findByText('Este deportista no tiene documentos vigentes.')).toBeInTheDocument();
    backend.clearOverrides();
    await userEvent.click(within(region).getByRole('button', { name: 'Reintentar' }));
    expect(await item('DNI – frente')).toHaveTextContent('Estado: Falta');
  });

  it('an athlete that does not exist requests no checklist', async () => {
    renderApp('/admin/deportistas/00000000-0000-4000-8000-0000000000ff');
    await screen.findByText(/No encontramos/);
    expect(checklistGets()).toHaveLength(0);
  });

  it('the athlete «Documentos» section keeps its anchor and focus target only for the athlete', async () => {
    await openDetail();
    expect(documents()).toHaveAttribute('id', 'documentos-admin-deportista');
    expect(documents()).toHaveAttribute('tabindex', '-1');
  });
});
