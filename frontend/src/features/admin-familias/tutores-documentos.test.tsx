import { cleanup, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { FakeAdminTutor, FakeBackend } from '@/test/fakeBackend';
import { createBackend, renderApp } from '@/test/render';

// MOCK backend (src/test/fakeBackend.ts mirrors GET /api/admin/tutores/{id}/documentos, .../{documentId}/archivo and
// .../{documentId}/revision, U19). Real-backend evidence lives in e2e/documentos-tutor.real.spec.ts.
let backend: FakeBackend;
const TUTOR: FakeAdminTutor = {
  id: 'tutor-rosa', nombre: 'Rosa', apellido: 'Sintética', dni: '30111222', dniEnmascarado: '••••••222', telefono: null, email: null,
  parentesco: 'Madre', activo: true, version: 1,
};
const section = () => screen.getByRole('region', { name: 'Documentos' });
const FRONT = 'DNI del tutor – frente';

beforeEach(() => {
  localStorage.clear();
  sessionStorage.clear();
  backend = createBackend();
  Object.defineProperty(URL, 'createObjectURL', { configurable: true, writable: true, value: vi.fn(() => 'blob:http://app.test/1') });
  Object.defineProperty(URL, 'revokeObjectURL', { configurable: true, writable: true, value: vi.fn() });
});

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

async function openTutor() {
  const family = backend.addFamily('Gómez', { tutores: [TUTOR] });
  backend.signIn('marcos@example.com');
  renderApp(`/admin/familias/${family.id}/tutores/${TUTOR.id}`);
  await screen.findByRole('heading', { level: 1, name: 'Rosa Sintética' });
}

describe('tutor documents in the administrative detail (U19)', () => {
  it('the tutor «Documentos» section is exactly as before U20: no anchor, no programmatic focus target', async () => {
    await openTutor();
    await within(section()).findByText('Este tutor no tiene documentos vigentes.');
    expect(section()).not.toHaveAttribute('id');
    expect(section()).not.toHaveAttribute('tabindex');
    expect(section().className).not.toContain('outline-none');
    // The partial checklist is about the athlete's DNI: a tutor's ficha neither shows nor requests it.
    expect(screen.queryByRole('region', { name: 'Checklist de documentación' })).not.toBeInTheDocument();
    expect(backend.requests.some((r) => r.url.includes('checklist'))).toBe(false);
  });

  it('says so when the tutor has no current documents', async () => {
    await openTutor();
    expect(await within(section()).findByText('Este tutor no tiene documentos vigentes.')).toBeInTheDocument();
  });

  it('lists the tutor DNI with their readable type, state, date and the reason only when observed', async () => {
    backend.seedDocument(TUTOR.id, { tipo: 'DNI_TUTOR_FRENTE', estado: 'CARGADO', creadoEn: '2026-03-10T14:00:00Z' });
    backend.seedDocument(TUTOR.id, { tipo: 'DNI_TUTOR_DORSO', estado: 'OBSERVADO', motivoObservacion: 'Borroso.' });
    await openTutor();
    const rows = await within(section()).findAllByRole('listitem');
    expect(rows.map((row) => within(row).getByRole('heading').textContent)).toEqual([FRONT, 'DNI del tutor – dorso']);
    expect(rows[0]).toHaveTextContent('Estado: Cargado');
    expect(rows[0]).toHaveTextContent('Fecha de carga: 10 mar 2026');
    expect(rows[1]).toHaveTextContent('Motivo de la observación: Borroso.');
    expect(backend.requests.filter((r) => /\/documentos\?/.test(r.url)).map((r) => r.url)).toEqual([`/admin/tutores/${TUTOR.id}/documentos?page=0&size=100`]);
    expect(backend.requests.some((r) => r.url.includes('/archivo'))).toBe(false); // nothing is fetched (nor audited) until asked
  });

  it('offers the review controls only for the tutor DNI types: any other type of the list gets only «Abrir documento»', async () => {
    backend.seedDocument(TUTOR.id, { tipo: 'DNI_TUTOR_FRENTE', estado: 'CARGADO' });
    backend.seedDocument(TUTOR.id, { tipo: 'DNI_DEPORTISTA_FRENTE', estado: 'CARGADO' });
    await openTutor();
    const rows = await within(section()).findAllByRole('listitem');
    expect(within(rows[0]!).getByRole('button', { name: /Aprobar documento/ })).toBeInTheDocument();
    expect(within(rows[1]!).queryByRole('button', { name: /Aprobar documento|Observar documento/ })).not.toBeInTheDocument();
    expect(within(rows[1]!).getByRole('button', { name: /Abrir documento/ })).toBeInTheDocument();
  });

  it('opens a document through the tutor route, and approves and observes with the version the list reported', async () => {
    const doc = backend.seedDocument(TUTOR.id, { tipo: 'DNI_TUTOR_FRENTE', estado: 'CARGADO' });
    await openTutor();
    const user = userEvent.setup();
    await user.click(await within(section()).findByRole('button', { name: `Abrir documento: ${FRONT}` }));
    expect(await within(section()).findByRole('region', { name: `Documento abierto: ${FRONT}` })).toBeInTheDocument();
    expect(backend.documentDownloads).toEqual([expect.objectContaining({ athleteId: TUTOR.id, documentId: doc.id, status: 200 })]);

    await user.click(within(section()).getByRole('button', { name: `Aprobar documento: ${FRONT}` }));
    await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Confirmar aprobación' }));
    await waitFor(() => expect(within(section()).getByRole('listitem')).toHaveTextContent('Estado: Aprobado'));
    const review = backend.requests.filter((r) => r.method === 'post' && r.url === `/admin/tutores/${TUTOR.id}/documentos/${doc.id}/revision`);
    expect(review).toHaveLength(1);
    expect(review[0]!.body).toEqual({ estado: 'APROBADO', versionEsperada: '0' });

    await user.click(within(section()).getByRole('button', { name: `Observar documento: ${FRONT}` }));
    const dialog = screen.getByRole('dialog');
    await user.type(within(dialog).getByLabelText('Motivo de la observación'), 'Falta el borde.');
    await user.click(within(dialog).getByRole('button', { name: 'Confirmar observación' }));
    await waitFor(() => expect(within(section()).getByRole('listitem')).toHaveTextContent('Estado: Observado'));
    expect(backend.requests.filter((r) => r.method === 'post' && r.url.endsWith('/revision')).map((r) => r.body)).toEqual([
      { estado: 'APROBADO', versionEsperada: '0' },
      { estado: 'OBSERVADO', versionEsperada: '1', motivoObservacion: 'Falta el borde.' },
    ]);
    // The athlete routes are never used for a tutor document.
    expect(backend.requests.some((r) => r.url.startsWith('/admin/deportistas/'))).toBe(false);
  });
});
