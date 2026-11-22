import { cleanup, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import type { FakeBackend, FakePendingDocument } from '@/test/fakeBackend';
import { createBackend, family, renderApp } from '@/test/render';

// MOCK backend (src/test/fakeBackend.ts mirrors GET /api/admin/documentos/pendientes). Real-backend evidence lives in
// e2e/documentos-pendientes.real.spec.ts.
let backend: FakeBackend;
const FAMILY_ID = 'fam-0001';

function row(over: Partial<FakePendingDocument> = {}): FakePendingDocument {
  return {
    id: 'doc-1', tipo: 'DNI_DEPORTISTA_FRENTE', creadoEn: '2026-03-10T14:00:00Z', titularTipo: 'DEPORTISTA', titularId: 'dep-1',
    titularNombre: 'Lucía Sintética', familiaId: null, reemplazo: false, ...over,
  };
}
const tutorRow = (over: Partial<FakePendingDocument> = {}) =>
  row({ id: 'doc-2', tipo: 'DNI_TUTOR_DORSO', titularTipo: 'TUTOR', titularId: 'tut-1', titularNombre: 'Rosa Sintética', familiaId: FAMILY_ID, ...over });

const location = () => screen.getByTestId('location').textContent ?? '';
const inboxGets = () => backend.requests.filter((r) => r.method === 'get' && r.url.startsWith('/admin/documentos/pendientes'));
const results = () => screen.getByRole('region', { name: 'Resultados' });

beforeEach(() => {
  localStorage.clear();
  sessionStorage.clear();
  backend = createBackend();
  backend.signIn('marcos@example.com');
});

afterEach(() => cleanup());

async function open(path = '/admin/documentos/pendientes') {
  renderApp(path);
  await screen.findByRole('heading', { level: 1, name: 'Documentos pendientes' });
}

describe('pending documents inbox (mock backend)', () => {
  it('lists each pending document with its owner, readable type, upload date and the replacement mark, without any scope parameter', async () => {
    backend.seedPendingInbox([row(), tutorRow({ reemplazo: true, creadoEn: '2026-03-11T02:30:00Z' })]);
    await open();

    const rows = await within(results()).findAllByRole('listitem');
    expect(rows).toHaveLength(2);
    expect(rows[0]).toHaveTextContent('Lucía Sintética');
    expect(rows[0]).toHaveTextContent('Deportista');
    expect(rows[0]).toHaveTextContent('DNI – frente · Cargado el 10 mar 2026');
    expect(rows[0]).not.toHaveTextContent('Reemplazo');
    expect(rows[1]).toHaveTextContent('Rosa Sintética');
    expect(rows[1]).toHaveTextContent('Tutor');
    expect(rows[1]).toHaveTextContent('DNI del tutor – dorso');
    // 02:30 UTC is still the 10th in Buenos Aires.
    expect(rows[1]).toHaveTextContent('Cargado el 10 mar 2026');
    expect(rows[1]).toHaveTextContent('Reemplazo de un documento observado');
    expect(within(results()).getByRole('status')).toHaveTextContent('2 documentos pendientes');
    expect(inboxGets().map((r) => r.url)).toEqual(['/admin/documentos/pendientes?page=0&size=20']);
  });

  it('every row links to the ficha where the review happens: the athlete, or the tutor inside their family', async () => {
    backend.seedPendingInbox([row(), tutorRow()]);
    await open();
    expect(await screen.findByRole('link', { name: 'Revisar DNI – frente de Lucía Sintética' })).toHaveAttribute('href', '/admin/deportistas/dep-1');
    const tutorLink = screen.getByRole('link', { name: 'Revisar DNI del tutor – dorso de Rosa Sintética' });
    expect(tutorLink).toHaveAttribute('href', `/admin/familias/${FAMILY_ID}/tutores/tut-1`);

    await userEvent.click(tutorLink);
    await waitFor(() => expect(location()).toBe(`/admin/familias/${FAMILY_ID}/tutores/tut-1`));
  });

  it('a tutor row without a family is listed but cannot be linked, and nothing else about the row changes', async () => {
    backend.seedPendingInbox([tutorRow({ familiaId: null })]);
    await open();
    expect(await within(results()).findByText('Rosa Sintética')).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: /Revisar/ })).not.toBeInTheDocument();
  });

  it('is reachable from the administrative menu', async () => {
    backend.seedPendingInbox([row()]);
    renderApp('/admin');
    await userEvent.click(await screen.findByRole('link', { name: 'Documentos pendientes' }));
    expect(await screen.findByRole('heading', { level: 1, name: 'Documentos pendientes' })).toBeInTheDocument();
    expect(location()).toBe('/admin/documentos/pendientes');
  });

  it('says so when there is nothing to review, and offers to clear a filter that leaves nothing', async () => {
    await open();
    expect(await screen.findByText('No hay documentos pendientes de revisión.')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Ver todos' })).not.toBeInTheDocument();
    cleanup();
    backend.seedPendingInbox([row()]);
    await open('/admin/documentos/pendientes?titular=TUTOR');
    expect(await screen.findByText('No hay documentos pendientes para ese titular.')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Ver todos' }));
    expect(await within(results()).findByText('Lucía Sintética')).toBeInTheDocument();
    expect(location()).toBe('/admin/documentos/pendientes');
  });

  it('filters by owner: the filter goes to the server and to the address, and «Todos» leaves both clean', async () => {
    backend.seedPendingInbox([row(), tutorRow()]);
    await open();
    await within(results()).findAllByRole('listitem');
    const user = userEvent.setup();
    await user.selectOptions(screen.getByLabelText('Titular'), 'TUTOR');
    await waitFor(() => expect(within(results()).getAllByRole('listitem')).toHaveLength(1));
    expect(within(results()).getByText('Rosa Sintética')).toBeInTheDocument();
    expect(location()).toBe('/admin/documentos/pendientes?titular=TUTOR');
    expect(inboxGets().at(-1)!.url).toBe('/admin/documentos/pendientes?page=0&size=20&titular=TUTOR');
    await user.selectOptions(screen.getByLabelText('Titular'), 'TODOS');
    await waitFor(() => expect(within(results()).getAllByRole('listitem')).toHaveLength(2));
    expect(location()).toBe('/admin/documentos/pendientes');
  });

  it('an unknown value in the address is the default filter and is never sent to the server', async () => {
    backend.seedPendingInbox([row(), tutorRow()]);
    await open('/admin/documentos/pendientes?titular=OTRO&pagina=abc');
    expect(await within(results()).findAllByRole('listitem')).toHaveLength(2);
    expect(inboxGets().map((r) => r.url)).toEqual(['/admin/documentos/pendientes?page=0&size=20']);
  });

  it('paginates: the page is in the address (from 1) and the server gets the page counted from 0', async () => {
    backend.seedPendingInbox(Array.from({ length: 25 }, (_, i) => row({ id: `doc-${String(i).padStart(2, '0')}`, titularId: `dep-${i}`, titularNombre: `Persona ${i}`, creadoEn: `2026-03-10T14:${String(i).padStart(2, '0')}:00Z` })));
    await open();
    expect(await within(results()).findAllByRole('listitem')).toHaveLength(20);
    expect(screen.getByRole('navigation', { name: 'Paginación' })).toHaveTextContent('Página 1 de 2 · 25 en total');
    await userEvent.click(screen.getByRole('button', { name: 'Siguiente' }));
    await waitFor(() => expect(within(results()).getAllByRole('listitem')).toHaveLength(5));
    expect(location()).toBe('/admin/documentos/pendientes?pagina=2');
    expect(inboxGets().at(-1)!.url).toBe('/admin/documentos/pendientes?page=1&size=20');
    expect(within(results()).getByText('Persona 24')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Siguiente' })).toBeDisabled();
  });

  it('a page that no longer exists says so and goes back to the first one', async () => {
    backend.seedPendingInbox([row()]);
    await open('/admin/documentos/pendientes?pagina=4');
    expect(await screen.findByText('Esa página ya no existe.')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Volver a la primera página' }));
    expect(await within(results()).findByText('Lucía Sintética')).toBeInTheDocument();
  });

  it('shows a loading state, then an error with a retry that the administrator chooses', async () => {
    backend.seedPendingInbox([row()]);
    backend.override('get', '/admin/documentos/pendientes?page=0&size=20', { status: 500, body: { codigo: 'ERROR_INTERNO', mensaje: 'x' } });
    renderApp('/admin/documentos/pendientes');
    expect(await screen.findByRole('alert')).toHaveTextContent('No se pudieron cargar los documentos.');
    backend.clearOverrides();
    await userEvent.click(screen.getByRole('button', { name: 'Reintentar' }));
    expect(await within(results()).findByText('Lucía Sintética')).toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('a response that does not follow the contract is an error, not a half-drawn list', async () => {
    backend.override('get', '/admin/documentos/pendientes?page=0&size=20', { status: 200, body: { contenido: [{ id: 'doc-1' }], totalElementos: 1 } });
    renderApp('/admin/documentos/pendientes');
    expect(await screen.findByRole('alert')).toHaveTextContent('No se pudieron cargar los documentos.');
    expect(screen.queryByRole('listitem')).not.toBeInTheDocument();
  });

  it('a family session never reaches the inbox and requests nothing', async () => {
    backend.signIn(family.email);
    renderApp('/admin/documentos/pendientes');
    expect(await screen.findByText('Tu cuenta no tiene acceso a esta área. Si creés que es un error, consultá con la escuela.')).toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: 'Documentos pendientes' })).not.toBeInTheDocument();
    expect(inboxGets()).toHaveLength(0);
  });
});
