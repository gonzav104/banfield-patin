import { act, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, useNavigate } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { App } from '@/app/App';
import { AppProviders, createQueryClient } from '@/app/providers';
import { sessionKey } from '@/features/auth/session';
import type { FakeBackend } from '@/test/fakeBackend';
import { admin, createBackend, renderApp } from '@/test/render';

// MOCK backend. Real-backend evidence lives in e2e/deportistas-datos-deportivos.real.spec.ts.
let backend: FakeBackend;

beforeEach(() => {
  localStorage.clear();
  sessionStorage.clear();
  backend = createBackend();
  backend.addUser({ ...admin, id: 'u-admin-3', nombre: 'Otra', email: 'otra.admin@example.com', mfaEnrolado: true });
});
afterEach(() => vi.restoreAllMocks());

const DNI = '30111322';
const CUIL = '20301113227';
const historyKey = (id: string) => ['admin', 'athlete-sports', id, 'history'];
const headerKey = (id: string) => ['admin', 'athlete-sports', id, 'header'];
const rowKey = (id: string, seasonId: string) => ['admin', 'athlete-sports', id, 'row', seasonId];
const location = () => screen.getByTestId('location').textContent ?? '';
const settle = () => act(async () => { await new Promise((r) => setTimeout(r, 400)); });

function seed() {
  const athlete = backend.addAdminAthlete({ nombre: 'Lucía', apellido: 'Sintética', dni: DNI, cuil: CUIL, domicilio: 'Calle Falsa 123' });
  const season = backend.addSeason(2027);
  backend.addSportsRow(athlete.id, season.id, { categoria: 'Sub 14', rama: 'Femenino', divisional: 'A', numeroCorredor: '007' });
  return { athlete, season };
}

/** The whole app plus a way to navigate programmatically (the screens have no link from one athlete to another). */
function renderWithNavigation(path: string) {
  const queryClient = createQueryClient();
  let navigateTo: (to: string) => void = () => undefined;
  function NavigationProbe() {
    const navigate = useNavigate();
    navigateTo = (to) => void navigate(to);
    return null;
  }
  render(
    <AppProviders client={queryClient}>
      <MemoryRouter initialEntries={[path]}>
        <App />
        <NavigationProbe />
      </MemoryRouter>
    </AppProviders>,
  );
  return {
    queryClient,
    go: (to: string) =>
      act(() => {
        navigateTo(to);
        return Promise.resolve();
      }),
  };
}

describe('sports data: a change of athlete while a response is still on its way', () => {
  it('a late history of the PREVIOUS athlete never reaches the screen of the next one', async () => {
    const a = seed();
    const b = backend.addAdminAthlete({ nombre: 'Eva', apellido: 'Gómez', dni: '30222333' });
    backend.addSportsRow(b.id, a.season.id, { categoria: 'Sub 10' });
    backend.signIn('marcos@example.com');
    const held = backend.hold('get', `/admin/deportistas/${a.athlete.id}/temporadas`);
    const { queryClient, go } = renderWithNavigation(`/admin/deportistas/${a.athlete.id}/temporadas`);
    await screen.findByText('Cargando los datos deportivos…');

    await go(`/admin/deportistas/${b.id}/temporadas`);
    expect(await screen.findByText('Eva Gómez')).toBeInTheDocument();
    held.release();
    await settle();

    expect(screen.getByText('Sub 10')).toBeInTheDocument();
    expect(screen.queryByText('Sub 14')).not.toBeInTheDocument();
    expect(screen.queryByText('Lucía Sintética')).not.toBeInTheDocument();
    expect(queryClient.getQueryData(historyKey(a.athlete.id))).toBeUndefined();
  });

  it('a late season row of the previous athlete never reaches the screen of the next one', async () => {
    const a = seed();
    const b = backend.addAdminAthlete({ nombre: 'Eva', apellido: 'Gómez', dni: '30222333' });
    const other = backend.addSeason(2026);
    backend.addSportsRow(b.id, other.id, { categoria: 'Sub 10' });
    backend.signIn('marcos@example.com');
    const held = backend.hold('get', `/admin/deportistas/${a.athlete.id}/temporadas/${a.season.id}`);
    const { queryClient, go } = renderWithNavigation(`/admin/deportistas/${a.athlete.id}/temporadas/${a.season.id}`);
    await screen.findByText('Cargando los datos deportivos…');

    await go(`/admin/deportistas/${b.id}/temporadas/${other.id}`);
    expect(await screen.findByRole('heading', { level: 1, name: 'Temporada 2026' })).toBeInTheDocument();
    held.release();
    await settle();

    expect(screen.getByRole('heading', { level: 1, name: 'Temporada 2026' })).toBeInTheDocument();
    expect(screen.queryByText('Sub 14')).not.toBeInTheDocument();
    expect(queryClient.getQueryData(rowKey(a.athlete.id, a.season.id))).toBeUndefined();
  });

  it('moving from the history to another season of the SAME athlete shows only that season', async () => {
    const a = seed();
    const t26 = backend.addSeason(2026);
    backend.addSportsRow(a.athlete.id, t26.id, { categoria: 'Sub 12' });
    backend.signIn('marcos@example.com');
    const held = backend.hold('get', `/admin/deportistas/${a.athlete.id}/temporadas/${a.season.id}`);
    const { go } = renderWithNavigation(`/admin/deportistas/${a.athlete.id}/temporadas/${a.season.id}`);
    await screen.findByText('Cargando los datos deportivos…');

    await go(`/admin/deportistas/${a.athlete.id}/temporadas/${t26.id}`);
    expect(await screen.findByRole('heading', { level: 1, name: 'Temporada 2026' })).toBeInTheDocument();
    held.release();
    await settle();

    expect(screen.getByText('Sub 12')).toBeInTheDocument();
    expect(screen.queryByText('Sub 14')).not.toBeInTheDocument();
  });
});

describe('sports data: a change of administrator', () => {
  it('a history answered AFTER the session changed to an identity that cannot see the athlete leaves nothing on screen or in the cache', async () => {
    const { athlete } = seed();
    backend.signIn('marcos@example.com');
    const held = backend.hold('get', `/admin/deportistas/${athlete.id}/temporadas`);
    const { queryClient } = renderApp(`/admin/deportistas/${athlete.id}/temporadas`);
    await screen.findByText('Cargando los datos deportivos…');

    backend.signIn('otra.admin@example.com');
    const notFound = { status: 404, body: { codigo: 'DEPORTISTA_NO_ENCONTRADO', mensaje: 'x' } };
    backend.override('get', `/admin/deportistas/${athlete.id}`, notFound, { once: false });
    backend.override('get', `/admin/deportistas/${athlete.id}/temporadas`, notFound, { once: false });
    await act(async () => { await queryClient.invalidateQueries({ queryKey: sessionKey }); });
    await screen.findByRole('heading', { name: 'No encontramos este deportista' });
    held.release();
    await settle();

    expect(screen.queryByText('Sub 14')).not.toBeInTheDocument();
    expect(screen.queryByRole('list', { name: 'Historial deportivo' })).not.toBeInTheDocument();
    expect(queryClient.getQueryData(historyKey(athlete.id))).toBeUndefined(); // OBSERVED: the previous identity's sports history
    expect(queryClient.getQueryData(headerKey(athlete.id))).toBeUndefined(); // OBSERVED: the previous identity's athlete name
  });

  it('a season row answered AFTER the session changed leaves nothing on screen or in the cache', async () => {
    const { athlete, season } = seed();
    backend.signIn('marcos@example.com');
    const held = backend.hold('get', `/admin/deportistas/${athlete.id}/temporadas/${season.id}`);
    const { queryClient } = renderApp(`/admin/deportistas/${athlete.id}/temporadas/${season.id}`);
    await screen.findByText('Cargando los datos deportivos…');

    backend.signIn('otra.admin@example.com');
    backend.override('get', `/admin/deportistas/${athlete.id}/temporadas/${season.id}`, { status: 404, body: { codigo: 'DEPORTISTA_NO_ENCONTRADO', mensaje: 'x' } }, { once: false });
    await act(async () => { await queryClient.invalidateQueries({ queryKey: sessionKey }); });
    await screen.findByRole('heading', { name: 'No encontramos este deportista' });
    held.release();
    await settle();

    expect(screen.queryByText('Sub 14')).not.toBeInTheDocument();
    expect(queryClient.getQueryData(rowKey(athlete.id, season.id))).toBeUndefined();
  });

  it('loaded sports data of one administrator is dropped from memory as soon as the session changes', async () => {
    const { athlete, season } = seed();
    backend.signIn('marcos@example.com');
    const { queryClient, go } = renderWithNavigation(`/admin/deportistas/${athlete.id}/temporadas`);
    await screen.findByRole('list', { name: 'Historial deportivo' });
    await go(`/admin/deportistas/${athlete.id}/temporadas/${season.id}`);
    await screen.findByRole('heading', { level: 1, name: 'Temporada 2027' });

    backend.signIn('otra.admin@example.com');
    backend.override('get', `/admin/deportistas/${athlete.id}/temporadas/${season.id}`, { status: 404, body: { codigo: 'DEPORTISTA_NO_ENCONTRADO', mensaje: 'x' } }, { once: false });
    await act(async () => { await queryClient.invalidateQueries({ queryKey: sessionKey }); });
    await screen.findByRole('heading', { name: 'No encontramos este deportista' });

    for (const key of [historyKey(athlete.id), headerKey(athlete.id), rowKey(athlete.id, season.id)]) expect(queryClient.getQueryData(key)).toBeUndefined();
    expect(screen.queryByText('Sub 14')).not.toBeInTheDocument();
  });

  it('keeps the sports rows only while a screen uses them (they belong to a minor)', async () => {
    const { athlete, season } = seed();
    backend.signIn('marcos@example.com');
    const { queryClient, go } = renderWithNavigation(`/admin/deportistas/${athlete.id}/temporadas/${season.id}`);
    await screen.findByRole('heading', { level: 1, name: 'Temporada 2027' });
    expect(queryClient.getQueryData(rowKey(athlete.id, season.id))).toBeDefined();

    await go('/admin/deportistas');
    await screen.findByRole('heading', { name: 'Deportistas' });
    await settle();

    expect(queryClient.getQueryData(rowKey(athlete.id, season.id))).toBeUndefined();
  });
});

describe('sports data: a response that does not belong to what was asked', () => {
  const SERVER_ERROR = 'Ocurrió un problema en el servidor. Intentá de nuevo en unos minutos.';
  const foreignRow = (athleteId: string, seasonId: string) => ({
    id: 'dt-x',
    deportistaId: athleteId,
    temporadaId: seasonId,
    anio: 2027,
    categoria: 'Sub 99',
    rama: null,
    divisional: null,
    numeroCorredor: null,
    estadoDeportivo: 'ACTIVO',
    version: '0',
  });

  it('a history that carries a row of ANOTHER athlete is not drawn', async () => {
    const { athlete, season } = seed();
    backend.override('get', `/admin/deportistas/${athlete.id}/temporadas`, { status: 200, body: [foreignRow('dep-de-otro', season.id)] });
    backend.signIn('marcos@example.com');
    renderApp(`/admin/deportistas/${athlete.id}/temporadas`);

    expect(await screen.findByText(SERVER_ERROR)).toBeInTheDocument();
    expect(screen.queryByText('Sub 99')).not.toBeInTheDocument();
  });

  it.each([
    ['another athlete', (_athleteId: string, seasonId: string) => foreignRow('dep-de-otro', seasonId)],
    ['another season', (athleteId: string) => foreignRow(athleteId, 'temp-de-otra')],
  ])('a season row of %s than the one requested is not drawn', async (_label, build) => {
    const { athlete, season } = seed();
    backend.override('get', `/admin/deportistas/${athlete.id}/temporadas/${season.id}`, { status: 200, body: build(athlete.id, season.id) });
    backend.signIn('marcos@example.com');
    renderApp(`/admin/deportistas/${athlete.id}/temporadas/${season.id}`);

    expect(await screen.findByText(SERVER_ERROR)).toBeInTheDocument();
    expect(screen.queryByText('Sub 99')).not.toBeInTheDocument();
  });
});

describe('sports data: privacy', () => {
  it('walks detail -> history -> season with only identifiers in the address, nothing in storage and nothing in the console', async () => {
    const { athlete, season } = seed();
    const spies = (['log', 'info', 'warn', 'error', 'debug'] as const).map((method) => vi.spyOn(console, method).mockImplementation(() => undefined));
    backend.signIn('marcos@example.com');
    renderApp(`/admin/deportistas/${athlete.id}`);
    const user = userEvent.setup();
    const seen: string[] = [];

    await screen.findByRole('heading', { level: 1, name: 'Lucía Sintética' });
    seen.push(location());
    await user.click(screen.getByRole('link', { name: 'Ver datos deportivos' }));
    await screen.findByRole('list', { name: 'Historial deportivo' });
    seen.push(location());
    await user.click(screen.getByRole('link', { name: 'Ver detalle de la temporada 2027' }));
    await screen.findByRole('heading', { level: 1, name: 'Temporada 2027' });
    seen.push(location());

    expect(seen).toEqual([`/admin/deportistas/${athlete.id}`, `/admin/deportistas/${athlete.id}/temporadas`, `/admin/deportistas/${athlete.id}/temporadas/${season.id}`]);
    const stored = JSON.stringify([{ ...localStorage }, { ...sessionStorage }]);
    const logged = spies.flatMap((spy) => spy.mock.calls).map((call) => call.map(String).join(' ')).join('\n');
    for (const secret of ['Lucía', 'Sintética', DNI, CUIL, 'Calle Falsa', 'Sub 14', 'Femenino', '007']) {
      expect(stored, `storage: ${secret}`).not.toContain(secret);
      expect(logged, `console: ${secret}`).not.toContain(secret);
    }
  });

  it('sends only the three read endpoints, with no personal data in any address', async () => {
    const { athlete, season } = seed();
    backend.signIn('marcos@example.com');
    const { go } = renderWithNavigation(`/admin/deportistas/${athlete.id}/temporadas`);
    await screen.findByRole('list', { name: 'Historial deportivo' });
    await go(`/admin/deportistas/${athlete.id}/temporadas/${season.id}`);
    await screen.findByRole('heading', { level: 1, name: 'Temporada 2027' });

    const sports = backend.requests.filter((r) => !r.url.startsWith('/auth/'));
    expect(sports.every((r) => r.method === 'get')).toBe(true);
    const allowed = [
      new RegExp(`^/admin/deportistas/${athlete.id}$`),
      new RegExp(`^/admin/deportistas/${athlete.id}/temporadas$`),
      new RegExp(`^/admin/deportistas/${athlete.id}/temporadas/${season.id}$`),
      /^\/admin\/temporadas\?page=0&size=1$/,
    ];
    for (const request of sports) expect(allowed.some((pattern) => pattern.test(request.url)), request.url).toBe(true);
    for (const request of sports) for (const secret of ['Lucía', DNI, CUIL, 'Sub 14']) expect(request.url).not.toContain(secret);
  });

  it('a screen of the history never draws the identification of the athlete even though the header comes from the full detail', async () => {
    const { athlete } = seed();
    backend.signIn('marcos@example.com');
    const { queryClient } = renderApp(`/admin/deportistas/${athlete.id}/temporadas`);
    await screen.findByRole('list', { name: 'Historial deportivo' });

    const header = queryClient.getQueryData<Record<string, unknown>>(headerKey(athlete.id));
    expect(header).toEqual({ id: athlete.id, nombre: 'Lucía', apellido: 'Sintética', activo: true });
    expect(JSON.stringify(queryClient.getQueryCache().getAll().map((q) => q.state.data))).not.toContain(DNI);
    expect(within(document.body).queryByText(new RegExp(DNI))).not.toBeInTheDocument();
  });
});

describe('sports data: the screens never wait for a session that is not ready', () => {
  it('an administrator whose MFA is pending is sent to the MFA step and nothing is requested', async () => {
    const { athlete } = seed();
    backend.addUser({ ...admin, id: 'u-admin-4', nombre: 'Pendiente', email: 'pendiente@example.com', mfaEnrolado: true });
    backend.signIn('pendiente@example.com', true);
    renderApp(`/admin/deportistas/${athlete.id}/temporadas`);

    await waitFor(() => expect(location()).toBe('/admin/mfa'));
    expect(backend.requests.filter((r) => /\/temporadas/.test(r.url))).toHaveLength(0);
  });
});
