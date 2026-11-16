import { act, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { QueryClient } from '@tanstack/react-query';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { sessionKey } from '@/features/auth/session';
import type { FakeAdminAthlete, FakeBackend } from '@/test/fakeBackend';
import { admin, createBackend, renderApp } from '@/test/render';

// MOCK backend (see src/test/fakeBackend.ts, which mirrors GET /api/admin/deportistas/{id}). Real-backend evidence lives in
// e2e/deportistas-consulta-alta.real.spec.ts.
let backend: FakeBackend;

beforeEach(() => {
  localStorage.clear();
  sessionStorage.clear();
  backend = createBackend();
});

const DNI = '30111322';
const CUIL = '20301113227';
const location = () => screen.getByTestId('location').textContent ?? '';
const detailGets = () => backend.requests.filter((r) => r.method === 'get' && /^\/admin\/deportistas\/[^/?]+$/.test(r.url));
const writes = () => backend.requests.filter((r) => r.method !== 'get' && r.url.startsWith('/admin/deportistas'));

function seedFull(over: Partial<FakeAdminAthlete> = {}) {
  return backend.addAdminAthlete({
    nombre: 'Lucía',
    apellido: 'Sintética',
    dni: DNI,
    cuil: CUIL,
    fechaNacimiento: '2012-05-04',
    nacionalidad: 'Argentina',
    domicilio: 'Calle Falsa 123',
    otrosDatosDomicilio: 'Piso 2, depto B',
    localidad: 'Banfield',
    partido: 'Lomas de Zamora',
    codigoPostal: '1828',
    telefonoContacto: '11 5555-0000',
    emailFederativo: 'lucia@example.test',
    ...over,
  });
}

function open(path: string): { queryClient: QueryClient } {
  backend.signIn('marcos@example.com');
  return renderApp(path);
}

describe('athlete detail: access (mock backend)', () => {
  it('sends anonymous visitors to the admin login and never requests the athlete', async () => {
    const athlete = seedFull();
    renderApp(`/admin/deportistas/${athlete.id}`);
    expect(await screen.findByRole('heading', { name: 'Acceso administrativo' })).toBeInTheDocument();
    expect(detailGets()).toHaveLength(0);
  });

  it('keeps a family user out and never requests the athlete', async () => {
    const athlete = seedFull();
    backend.signIn('lucia@example.com');
    renderApp(`/admin/deportistas/${athlete.id}`);
    expect(await screen.findByRole('heading', { name: 'No tenés permiso para ver esta sección' })).toBeInTheDocument();
    expect(detailGets()).toHaveLength(0);
    expect(document.body).not.toHaveTextContent(DNI);
  });
});

describe('athlete detail: data (mock backend)', () => {
  it('shows every personal datum of the contract, with the FULL DNI and CUIL, and the status', async () => {
    const athlete = seedFull();
    open(`/admin/deportistas/${athlete.id}`);
    expect(await screen.findByRole('heading', { level: 1, name: 'Lucía Sintética' })).toBeInTheDocument();
    expect(detailGets()[0]!.url).toBe(`/admin/deportistas/${athlete.id}`);
    const text = document.body.textContent ?? '';
    for (const expected of [DNI, CUIL, '04/05/2012', 'Argentina', '11 5555-0000', 'lucia@example.test', 'Calle Falsa 123', 'Piso 2, depto B', 'Banfield', 'Lomas de Zamora', '1828']) {
      expect(text, expected).toContain(expected);
    }
    expect(screen.getByText('Activo')).toBeInTheDocument();
    expect(screen.getByText('Son datos personales: solo los ven los administradores.')).toBeInTheDocument();
  });

  it('says «No informado» for every optional datum the backend does not have', async () => {
    const athlete = backend.addAdminAthlete({ nombre: 'Mínimo', apellido: 'Sintético', dni: '40111222' });
    open(`/admin/deportistas/${athlete.id}`);
    await screen.findByRole('heading', { level: 1, name: 'Mínimo Sintético' });
    expect(screen.getAllByText('No informado')).toHaveLength(10); // CUIL, birth, nationality, phone, e-mail, address (5)
    expect(screen.getByText('40111222')).toBeInTheDocument();
  });

  it('marks an inactive athlete as inactive in writing', async () => {
    const athlete = seedFull({ activo: false });
    open(`/admin/deportistas/${athlete.id}`);
    await screen.findByRole('heading', { level: 1, name: 'Lucía Sintética' });
    expect(screen.getByText('Inactivo')).toBeInTheDocument();
    expect(screen.queryByText('Activo')).not.toBeInTheDocument();
  });

  it('is read-only: no edit, status or write control, no write request, and the revision is not shown', async () => {
    const athlete = seedFull();
    athlete.version = 7;
    open(`/admin/deportistas/${athlete.id}`);
    await screen.findByRole('heading', { level: 1, name: 'Lucía Sintética' });
    expect(screen.queryByRole('button', { name: /editar|guardar|desactivar|activar/i })).not.toBeInTheDocument();
    expect(screen.queryByRole('link', { name: /editar/i })).not.toBeInTheDocument();
    expect(screen.queryByText(/revisión|versión/i)).not.toBeInTheDocument();
    expect(writes()).toHaveLength(0);
  });

  it('does not show family, season or sports data (other units)', async () => {
    const athlete = seedFull();
    open(`/admin/deportistas/${athlete.id}`);
    await screen.findByRole('heading', { level: 1, name: 'Lucía Sintética' });
    expect(within(screen.getByRole('main')).queryByText(/familia|temporada|categor/i)).not.toBeInTheDocument();
  });

  it('does not take the notice of a created athlete unless it arrives in the navigation state', async () => {
    const athlete = seedFull();
    open(`/admin/deportistas/${athlete.id}`);
    await screen.findByRole('heading', { level: 1, name: 'Lucía Sintética' });
    expect(screen.queryByText(/Deportista creado/)).not.toBeInTheDocument();
  });

  it('goes back to the list', async () => {
    const athlete = seedFull();
    open(`/admin/deportistas/${athlete.id}`);
    await screen.findByRole('heading', { level: 1, name: 'Lucía Sintética' });
    await userEvent.setup().click(screen.getByRole('link', { name: 'Volver al listado' }));
    expect(await screen.findByRole('heading', { name: 'Deportistas' })).toBeInTheDocument();
    expect(location()).toBe('/admin/deportistas');
  });
});

describe('athlete detail: errors (mock backend)', () => {
  it('shows one generic «not found» for an id that does not exist (or belongs to another school: the backend answers the same 404)', async () => {
    open('/admin/deportistas/no-existe');
    expect(await screen.findByRole('heading', { name: 'No encontramos este deportista' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Volver al listado' })).toBeInTheDocument();
    expect(document.body).not.toHaveTextContent(/escuela|otra|DEPORTISTA_NO_ENCONTRADO/i);
  });

  it('shows the same screen for an id the backend cannot read (400), without leaking its message', async () => {
    const athlete = seedFull();
    backend.override('get', `/admin/deportistas/${athlete.id}`, { status: 400, body: { codigo: 'SOLICITUD_INVALIDA', mensaje: 'detalle interno' } });
    open(`/admin/deportistas/${athlete.id}`);
    expect(await screen.findByRole('heading', { name: 'No encontramos este deportista' })).toBeInTheDocument();
    expect(document.body).not.toHaveTextContent('detalle interno');
  });

  it('never requests an id that could alter the path', async () => {
    open('/admin/deportistas/..%2Fadmin');
    expect(await screen.findByRole('heading', { name: 'No encontramos este deportista' })).toBeInTheDocument();
    expect(detailGets()).toHaveLength(0);
  });

  it('explains a permission error, a server error and a network failure, with a working retry', async () => {
    const athlete = seedFull();
    const url = `/admin/deportistas/${athlete.id}`;
    backend.override('get', url, { status: 403, body: { codigo: 'ACCESO_DENEGADO', mensaje: 'x' } }, { once: true });
    open(url);
    expect(await screen.findByRole('alert')).toHaveTextContent('No tenés permiso para ver este deportista.');
    backend.override('get', url, { status: 500, body: { codigo: 'ERROR_INTERNO', mensaje: 'detalle interno' } }, { once: true });
    await userEvent.setup().click(screen.getByRole('button', { name: 'Reintentar' }));
    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('Ocurrió un problema en el servidor'));
    expect(screen.getByRole('alert')).not.toHaveTextContent('detalle interno');
    await userEvent.setup().click(screen.getByRole('button', { name: 'Reintentar' }));
    expect(await screen.findByRole('heading', { level: 1, name: 'Lucía Sintética' })).toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('explains a network failure', async () => {
    const athlete = seedFull();
    backend.override('get', `/admin/deportistas/${athlete.id}`, 'network');
    open(`/admin/deportistas/${athlete.id}`);
    expect(await screen.findByRole('alert')).toHaveTextContent('No pudimos conectar con el servidor');
  });

  it('shows a loading state while the athlete is on its way', async () => {
    const athlete = seedFull();
    const held = backend.hold('get', `/admin/deportistas/${athlete.id}`);
    open(`/admin/deportistas/${athlete.id}`);
    expect(await screen.findByText('Cargando el deportista…')).toBeInTheDocument();
    expect(document.body).not.toHaveTextContent(DNI);
    held.release();
    expect(await screen.findByRole('heading', { level: 1, name: 'Lucía Sintética' })).toBeInTheDocument();
  });

  it.each([
    ['a missing revision', undefined],
    ['a non-decimal revision', 'abc'],
    ['a negative revision', '-1'],
  ])('refuses a response with %s instead of drawing it', async (_name, version) => {
    const athlete = seedFull();
    const { version: _omit, ...rest } = backend['athleteDetail'](athlete) as Record<string, unknown>;
    void _omit;
    backend.override('get', `/admin/deportistas/${athlete.id}`, { status: 200, body: version === undefined ? rest : { ...rest, version } });
    open(`/admin/deportistas/${athlete.id}`);
    expect(await screen.findByRole('alert')).toHaveTextContent('Ocurrió un problema en el servidor');
    expect(document.body).not.toHaveTextContent(DNI);
  });
});

describe('athlete detail: privacy and identity (mock backend)', () => {
  it('keeps the personal data out of the address, storage and console, and drops it from memory on leaving', async () => {
    const spies = (['log', 'info', 'warn', 'error', 'debug'] as const).map((level) => vi.spyOn(console, level).mockImplementation(() => undefined));
    const pattern = new RegExp(`${DNI}|${CUIL}|lucia@|5555|Falsa`);
    let queryClient: QueryClient;
    try {
      const athlete = seedFull();
      ({ queryClient } = open(`/admin/deportistas/${athlete.id}`));
      await screen.findByRole('heading', { level: 1, name: 'Lucía Sintética' });
      expect(location()).not.toMatch(pattern);
      expect(JSON.stringify({ ...localStorage, ...sessionStorage })).not.toMatch(pattern);
      await userEvent.setup().click(screen.getByRole('link', { name: 'Volver al listado' }));
      await screen.findByRole('heading', { name: 'Deportistas' });
      await waitFor(() => expect(queryClient.getQueryData(['admin', 'athlete-detail', athlete.id])).toBeUndefined());
      expect(spies.flatMap((spy) => spy.mock.calls).flat().join(' ')).not.toMatch(pattern);
    } finally {
      spies.forEach((spy) => spy.mockRestore());
    }
  });

  it('asks for the athlete again, from nothing, when the session now belongs to another administrator', async () => {
    backend.addUser({ ...admin, id: 'u-admin-3', nombre: 'Otra', email: 'otra.admin@example.com', mfaEnrolado: true });
    const athlete = seedFull();
    const { queryClient } = open(`/admin/deportistas/${athlete.id}`);
    await screen.findByRole('heading', { level: 1, name: 'Lucía Sintética' });
    expect(detailGets()).toHaveLength(1);
    backend.signIn('otra.admin@example.com');
    await act(async () => {
      await queryClient.invalidateQueries({ queryKey: sessionKey });
    });
    await screen.findByRole('heading', { level: 1, name: 'Lucía Sintética' });
    await waitFor(() => expect(detailGets().length).toBeGreaterThanOrEqual(2)); // nothing of the previous identity was reused
  });

  it('shows nothing of the previous athlete while the next one loads (the resource changed)', async () => {
    const first = seedFull();
    const second = backend.addAdminAthlete({ nombre: 'Otro', apellido: 'Distinto', dni: '41999888' });
    open('/admin/deportistas');
    const user = userEvent.setup();
    await user.click(await screen.findByRole('link', { name: 'Distinto, Otro' }));
    await screen.findByRole('heading', { level: 1, name: 'Otro Distinto' });
    expect(document.body).toHaveTextContent('41999888');
    expect(first.id).not.toBe(second.id);
    const held = backend.hold('get', `/admin/deportistas/${first.id}`);
    await user.click(screen.getByRole('link', { name: 'Volver al listado' }));
    await user.click(await screen.findByRole('link', { name: 'Sintética, Lucía' }));
    expect(await screen.findByText('Cargando el deportista…')).toBeInTheDocument();
    expect(document.body).not.toHaveTextContent('41999888');
    held.release();
    expect(await screen.findByRole('heading', { level: 1, name: 'Lucía Sintética' })).toBeInTheDocument();
  });
});
