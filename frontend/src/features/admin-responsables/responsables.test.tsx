import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it } from 'vitest';
import { seedAthlete } from '@/features/admin-vinculos/links-fixtures';
import type { FakeBackend } from '@/test/fakeBackend';
import { createBackend, renderApp } from '@/test/render';
import { getResponsables } from './api';
import { responsablesPageSchema } from './schemas';

// MOCK backend (src/test/fakeBackend.ts mirrors GET /api/admin/deportistas/{id}/vinculos-tutores).
// Real-backend evidence lives in e2e/responsables-admin.real.spec.ts.
let backend: FakeBackend;
const path = (id: string) => `/admin/deportistas/${id}/responsables`;
const gets = () => backend.requests.filter((r) => r.method === 'get' && /\/vinculos-tutores/.test(r.url));
const writes = () => backend.requests.filter((r) => r.method !== 'get' && !r.url.startsWith('/auth/'));

function open(id: string) {
  backend.signIn('marcos@example.com');
  return renderApp(path(id));
}

function seed() {
  const athlete = seedAthlete(backend);
  const family = backend.addFamily('Familia Gómez');
  const abierta = backend.addResponsable(athlete.id, family.id, { nombre: 'Ana', apellido: 'Alvarez', tipo: 'MADRE', creadoEn: '2026-03-01T12:00:00Z' });
  const sinTipo = backend.addResponsable(athlete.id, family.id, { nombre: 'Beto', apellido: 'Benitez', tipo: null, creadoEn: '2026-03-02T12:00:00Z' });
  const cerrada = backend.addResponsable(athlete.id, family.id, { nombre: 'Carla', apellido: 'Campos', tipo: 'SIN_DETERMINAR', creadoEn: '2026-02-01T12:00:00Z' });
  backend.closeResponsableBehindTheScenes(cerrada.id, 'REVOCACION_ADMIN');
  return { athlete, family, abierta, sinTipo, cerrada };
}

beforeEach(() => {
  localStorage.clear();
  sessionStorage.clear();
  backend = createBackend();
});

describe('responsables of an athlete: the contract (mock backend)', () => {
  it('lists only the open ones by default and every row with the history flag', async () => {
    const { athlete } = seed();
    backend.signIn('marcos@example.com');
    expect((await getResponsables(athlete.id, false, 0)).contenido.map((r) => r.nombreTutor)).toEqual(['Beto', 'Ana']);
    const all = await getResponsables(athlete.id, true, 0);
    expect(all.contenido.map((r) => r.nombreTutor)).toEqual(['Beto', 'Ana', 'Carla']);
    expect(all.contenido.find((r) => r.nombreTutor === 'Carla')).toMatchObject({ causaCierre: 'REVOCACION_ADMIN' });
  });

  it('keeps a null type apart from SIN_DETERMINAR and drops what the server may add', () => {
    const base = { id: 'r', familiaId: 'f', tutorId: 't', nombreTutor: 'A', apellidoTutor: 'B', creadoEn: '2026-03-01T12:00:00Z', cerradoEn: null, causaCierre: null, version: '0' };
    const page = (tipo: unknown, extra: Record<string, unknown> = {}) => ({ contenido: [{ ...base, tipo, ...extra }], pagina: 0, tamanio: 20, totalElementos: 1 });
    expect(responsablesPageSchema.parse(page(null)).contenido[0]!.tipo).toBeNull();
    expect(responsablesPageSchema.parse(page('SIN_DETERMINAR')).contenido[0]!.tipo).toBe('SIN_DETERMINAR');
    expect(Object.keys(responsablesPageSchema.parse(page('MADRE', { dni: '30111222', email: 'x@y.z', solicitudId: 's' })).contenido[0]!)).not.toContain('dni');
    expect(responsablesPageSchema.safeParse(page('ABUELO')).success).toBe(false);
    expect(responsablesPageSchema.safeParse(page('MADRE', { causaCierre: 'OTRA' })).success).toBe(false);
    expect(responsablesPageSchema.safeParse(page('MADRE', { version: '-1' })).success).toBe(false);
  });

  it('treats a closed row in an open-only answer, or a half-closed one, as a malformed answer', async () => {
    const { athlete } = seed();
    backend.signIn('marcos@example.com');
    const closed = { id: 'r', familiaId: 'f', tutorId: 't', nombreTutor: 'A', apellidoTutor: 'B', tipo: null, creadoEn: '2026-03-01T12:00:00Z', cerradoEn: '2026-04-01T12:00:00Z', causaCierre: 'REVOCACION_ADMIN', version: '1' };
    const url = (h: boolean) => `/admin/deportistas/${athlete.id}/vinculos-tutores?historial=${h}&page=0&size=20`;
    backend.override('get', url(false), { status: 200, body: { contenido: [closed], pagina: 0, tamanio: 20, totalElementos: 1 } });
    await expect(getResponsables(athlete.id, false, 0)).rejects.toMatchObject({ kind: 'server' });
    backend.override('get', url(true), { status: 200, body: { contenido: [{ ...closed, causaCierre: null }], pagina: 0, tamanio: 20, totalElementos: 1 } });
    await expect(getResponsables(athlete.id, true, 0)).rejects.toMatchObject({ kind: 'server' });
  });
});

describe('responsables of an athlete: what the administrator sees (mock backend)', () => {
  it('names the athlete, explains what the type is not and lists the open responsables with their plain-language type', async () => {
    const { athlete } = seed();
    open(athlete.id);

    expect(await screen.findByRole('heading', { level: 1, name: 'Responsables' })).toBeInTheDocument();
    expect(screen.getByText('Lucía Sintética')).toBeInTheDocument();
    expect(screen.getByText(/no acredita representación legal/)).toBeInTheDocument();
    const section = screen.getByRole('region', { name: 'Responsables vigentes' });
    const items = await within(section).findAllByRole('listitem');
    expect(items.map((i) => i.textContent)).toEqual([expect.stringContaining('Beto Benitez'), expect.stringContaining('Ana Alvarez')]);
    expect(items[0]).toHaveTextContent('Sin declarar');
    expect(items[0]).not.toHaveTextContent('Sin determinar');
    expect(items[1]).toHaveTextContent('Madre');
    expect(section).not.toHaveTextContent(/Carla/);
  });

  it('puts the keyboard focus on the title and never shows revisions, ids or personal data', async () => {
    const { athlete } = seed();
    open(athlete.id);
    const title = await screen.findByRole('heading', { level: 1, name: 'Responsables' });
    await waitFor(() => expect(title).toHaveFocus());
    expect(document.body).not.toHaveTextContent(/30111322|20301113227|Calle Falsa|987654321/);
  });

  it('does not ask for the history until it is requested, then shows who closed what and why, and hides it again', async () => {
    const { athlete } = seed();
    open(athlete.id);
    await screen.findByRole('region', { name: 'Responsables vigentes' });
    expect(gets().every((r) => r.url.includes('historial=false'))).toBe(true);
    const toggle = screen.getByRole('button', { name: 'Ver historial' });
    expect(toggle).toHaveAttribute('aria-expanded', 'false');

    await userEvent.click(toggle);
    expect(screen.getByRole('button', { name: 'Ocultar historial' })).toHaveAttribute('aria-expanded', 'true');
    const history = screen.getByRole('region', { name: 'Historial' });
    const closed = (await within(history).findByText('Carla Campos')).closest('li')!;
    expect(closed).toHaveTextContent('Cerrada');
    expect(closed).toHaveTextContent('Revocada por el club');
    expect(closed).toHaveTextContent('Sin determinar');
    expect(within(history).getByText('Ana Alvarez').closest('li')).toHaveTextContent('Vigente');
    expect(within(history).getByText(/no se reabre/)).toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: 'Ocultar historial' }));
    expect(within(history).queryByText('Carla Campos')).not.toBeInTheDocument();
  });

  it('shows the empty states of the open list and of the history', async () => {
    const athlete = seedAthlete(backend);
    open(athlete.id);
    expect(await screen.findByText('Este deportista no tiene responsables vigentes.')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Ver historial' }));
    expect(await screen.findByText('Todavía no hay responsabilidades cerradas para este deportista.')).toBeInTheDocument();
  });

  it('shows a retryable error with a fixed message, never the backend text', async () => {
    const { athlete } = seed();
    backend.override('get', `/admin/deportistas/${athlete.id}/vinculos-tutores?historial=false&page=0&size=20`, { status: 500, body: { codigo: 'ERROR', mensaje: 'SECRETO-INTERNO' } }, { once: true });
    open(athlete.id);
    const alert = await screen.findByRole('alert');
    expect(alert).not.toHaveTextContent('SECRETO-INTERNO');
    await userEvent.click(screen.getByRole('button', { name: 'Reintentar' }));
    expect(await screen.findByText('Ana Alvarez')).toBeInTheDocument();
  });

  it('is one «not found» screen for an unknown athlete and makes no writes at all', async () => {
    open('11111111-1111-4111-8111-111111111111');
    expect(await screen.findByText(/no encontr|no existe/i)).toBeInTheDocument();
    expect(writes()).toEqual([]);
  });

  it('is reachable from the athlete detail through a link, and the screen it opens is read-only', async () => {
    const { athlete } = seed();
    backend.signIn('marcos@example.com');
    renderApp(`/admin/deportistas/${athlete.id}`);
    const card = await screen.findByRole('region', { name: 'Responsables' });
    expect(within(card).getByRole('link', { name: 'Ver responsables' })).toHaveAttribute('href', path(athlete.id));
    await userEvent.click(within(card).getByRole('link', { name: 'Ver responsables' }));
    await screen.findByRole('region', { name: 'Responsables vigentes' });
    expect(screen.queryByRole('button', { name: /agregar|revocar|editar|activar|desactivar/i })).not.toBeInTheDocument();
    expect(writes()).toEqual([]);
  });
});
