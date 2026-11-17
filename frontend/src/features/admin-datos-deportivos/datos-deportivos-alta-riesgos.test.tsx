import { act, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, useLocation, useNavigate } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { App } from '@/app/App';
import { AppProviders, createQueryClient } from '@/app/providers';
import { sessionKey } from '@/features/auth/session';
import type { FakeBackend } from '@/test/fakeBackend';
import { admin, createBackend, renderApp } from '@/test/render';

// MOCK backend. Real-backend evidence lives in e2e/deportistas-datos-deportivos-alta.real.spec.ts.
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
const rowKey = (athleteId: string, seasonId: string) => ['admin', 'athlete-sports', athleteId, 'row', seasonId];
const headerKey = (athleteId: string) => ['admin', 'athlete-sports', athleteId, 'header'];
const location = () => screen.getByTestId('location').textContent ?? '';
const alta = (id: string) => `/admin/deportistas/${id}/temporadas/nueva`;
const posts = () => backend.requests.filter((r) => r.method === 'post' && /^\/admin\/deportistas\/[^/]+\/temporadas$/.test(r.url));
const settle = () => act(async () => { await new Promise((r) => setTimeout(r, 400)); });
const seasonSelect = () => screen.getByLabelText(/^Temporada/);
const submitButton = () => screen.getByRole('button', { name: /^(Cargar datos|Cargando…)$/ });
const field = (label: RegExp) => screen.getByLabelText(label);

function seed() {
  const athlete = backend.addAdminAthlete({ nombre: 'Lucía', apellido: 'Sintética', dni: DNI, cuil: CUIL, domicilio: 'Calle Falsa 123' });
  const season = backend.addSeason(2027);
  return { athlete, season };
}

/** The whole app plus a way to navigate programmatically (the screens have no link from one athlete to another). */
function renderWithNavigation(path: string) {
  const queryClient = createQueryClient();
  let navigateTo: (to: string) => void = () => undefined;
  function NavigationProbe() {
    const navigate = useNavigate();
    const current = useLocation();
    navigateTo = (to) => void navigate(to);
    return <output data-testid="location">{`${current.pathname}${current.search}${current.hash}`}</output>;
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

describe('load sports data: a request that is still on its way when things change', () => {
  it('a 201 that arrives AFTER the session changed to another identity neither navigates nor leaves anything in the cache', async () => {
    const { athlete, season } = seed();
    backend.signIn('marcos@example.com');
    const { queryClient } = renderApp(alta(athlete.id));
    const user = userEvent.setup();
    await screen.findByRole('form', { name: 'Cargar datos deportivos' });
    await user.selectOptions(seasonSelect(), within(seasonSelect()).getByRole('option', { name: 'Temporada 2027' }));
    await user.type(field(/^Categoría/), 'Sub 14');
    const held = backend.hold('post', `/admin/deportistas/${athlete.id}/temporadas`);
    await user.click(submitButton());
    await vi.waitFor(() => expect(posts()).toHaveLength(1));

    backend.signIn('otra.admin@example.com');
    const notFound = { status: 404, body: { codigo: 'DEPORTISTA_NO_ENCONTRADO', mensaje: 'x' } };
    backend.override('get', `/admin/deportistas/${athlete.id}`, notFound, { once: false });
    backend.override('get', `/admin/deportistas/${athlete.id}/temporadas`, notFound, { once: false });
    await act(async () => { await queryClient.invalidateQueries({ queryKey: sessionKey }); });
    await screen.findByRole('heading', { name: 'No encontramos este deportista' });
    held.release();
    await settle();

    expect(location()).toBe(alta(athlete.id)); // it never went to the detail of the new row
    expect(screen.queryByText('Datos deportivos cargados.')).not.toBeInTheDocument();
    expect(screen.queryByText('Sub 14')).not.toBeInTheDocument();
    expect(queryClient.getQueryData(rowKey(athlete.id, season.id))).toBeUndefined();
    expect(queryClient.getQueryData(headerKey(athlete.id))).toBeUndefined();
  });

  it('a 201 that arrives AFTER the identity changed does not even make the NEW identity reload what it is looking at', async () => {
    const { athlete } = seed();
    backend.signIn('marcos@example.com');
    const { queryClient } = renderApp(alta(athlete.id));
    const user = userEvent.setup();
    await screen.findByRole('form', { name: 'Cargar datos deportivos' });
    await user.selectOptions(seasonSelect(), within(seasonSelect()).getByRole('option', { name: 'Temporada 2027' }));
    const held = backend.hold('post', `/admin/deportistas/${athlete.id}/temporadas`);
    await user.click(submitButton());
    await vi.waitFor(() => expect(posts()).toHaveLength(1));

    // Another administrator who CAN see the athlete takes the session: its own screen loads from nothing.
    backend.signIn('otra.admin@example.com');
    await act(async () => { await queryClient.invalidateQueries({ queryKey: sessionKey }); });
    await screen.findByRole('form', { name: 'Cargar datos deportivos' });
    const readsBefore = backend.requests.filter((r) => r.method === 'get' && /^\/admin\/(deportistas|temporadas)/.test(r.url)).length;
    held.release();
    await settle();

    const readsAfter = backend.requests.filter((r) => r.method === 'get' && /^\/admin\/(deportistas|temporadas)/.test(r.url)).length;
    expect(readsAfter).toBe(readsBefore); // the late answer belongs to the previous identity: it refreshes nothing of the new one
    expect(location()).toBe(alta(athlete.id));
    expect(screen.queryByText('Datos deportivos cargados.')).not.toBeInTheDocument();
  });

  it('a 201 that arrives AFTER the administrator moved to another athlete never takes them to the first one\'s row', async () => {
    const { athlete: a, season } = seed();
    const b = backend.addAdminAthlete({ nombre: 'Eva', apellido: 'Gómez', dni: '30222333' });
    backend.signIn('marcos@example.com');
    const { go } = renderWithNavigation(alta(a.id));
    const user = userEvent.setup();
    await screen.findByRole('form', { name: 'Cargar datos deportivos' });
    await user.selectOptions(seasonSelect(), within(seasonSelect()).getByRole('option', { name: 'Temporada 2027' }));
    await user.type(field(/^Categoría/), 'Sub 14');
    const held = backend.hold('post', `/admin/deportistas/${a.id}/temporadas`);
    await user.click(submitButton());
    await vi.waitFor(() => expect(posts()).toHaveLength(1));

    await go(alta(b.id));
    await screen.findByRole('form', { name: 'Cargar datos deportivos' });
    held.release();
    await settle();

    expect(location()).toBe(alta(b.id));
    expect(screen.queryByText('Datos deportivos cargados.')).not.toBeInTheDocument();
    expect(screen.getByRole('form', { name: 'Cargar datos deportivos' })).toBeInTheDocument();
    expect(backend.sportsRowsOf(a.id).map((r) => r.temporadaId)).toEqual([season.id]); // the server did apply it: the screen just does not pretend to know
    expect(backend.sportsRowsOf(b.id)).toHaveLength(0);
  });

  it('the draft of one athlete never reaches the form of the next one', async () => {
    const { athlete: a } = seed();
    const b = backend.addAdminAthlete({ nombre: 'Eva', apellido: 'Gómez', dni: '30222333' });
    backend.signIn('marcos@example.com');
    const { go } = renderWithNavigation(alta(a.id));
    const user = userEvent.setup();
    await screen.findByRole('form', { name: 'Cargar datos deportivos' });
    await user.selectOptions(seasonSelect(), within(seasonSelect()).getByRole('option', { name: 'Temporada 2027' }));
    await user.type(field(/^Categoría/), 'Sub 14 reservada');
    await user.type(field(/^Número de corredor/), '0042');

    await go(alta(b.id));
    // the unsaved-changes guard only intercepts clicks on links; a programmatic navigation just changes the screen
    await screen.findByRole('form', { name: 'Cargar datos deportivos' });

    expect(field(/^Categoría/)).toHaveValue('');
    expect(field(/^Número de corredor/)).toHaveValue('');
    expect(seasonSelect()).toHaveValue('');
  });

  it('a verification that answers after the administrator changed athlete shows nothing of the first one', async () => {
    const { athlete: a, season } = seed();
    const b = backend.addAdminAthlete({ nombre: 'Eva', apellido: 'Gómez', dni: '30222333' });
    backend.signIn('marcos@example.com');
    const { go } = renderWithNavigation(alta(a.id));
    const user = userEvent.setup();
    await screen.findByRole('form', { name: 'Cargar datos deportivos' });
    backend.override('post', `/admin/deportistas/${a.id}/temporadas`, 'network');
    await user.selectOptions(seasonSelect(), within(seasonSelect()).getByRole('option', { name: 'Temporada 2027' }));
    await user.type(field(/^Categoría/), 'Sub 14');
    await user.click(submitButton());
    await screen.findByRole('button', { name: 'Verificar la temporada' });
    backend.addSportsRow(a.id, season.id, { categoria: 'Sub 14' });
    const held = backend.hold('get', `/admin/deportistas/${a.id}/temporadas/${season.id}`);
    await user.click(screen.getByRole('button', { name: 'Verificar la temporada' }));
    await vi.waitFor(() => expect(backend.requests.filter((r) => r.url === `/admin/deportistas/${a.id}/temporadas/${season.id}`)).toHaveLength(1));

    await go(alta(b.id));
    await screen.findByRole('form', { name: 'Cargar datos deportivos' });
    held.release();
    await settle();

    expect(screen.queryByRole('group', { name: 'Verificación' })).not.toBeInTheDocument();
    expect(screen.queryByText('Sub 14')).not.toBeInTheDocument();
    expect(field(/^Categoría/)).toHaveValue('');
  });
});

describe('load sports data: privacy', () => {
  it('keeps the draft out of the address, the browser storage and the console, and never reads or sends the DNI or the CUIL', async () => {
    const { athlete } = seed();
    const spies = (['log', 'info', 'warn', 'error', 'debug'] as const).map((method) => vi.spyOn(console, method).mockImplementation(() => undefined));
    backend.signIn('marcos@example.com');
    const { queryClient } = renderApp(alta(athlete.id));
    const user = userEvent.setup();
    await screen.findByRole('form', { name: 'Cargar datos deportivos' });
    await user.selectOptions(seasonSelect(), within(seasonSelect()).getByRole('option', { name: 'Temporada 2027' }));
    await user.type(field(/^Categoría/), 'Sub 14 reservada');
    await user.type(field(/^Rama/), 'Femenino');
    await user.type(field(/^Divisional/), 'Zeta');
    await user.type(field(/^Número de corredor/), '0042');

    expect(location()).toBe(alta(athlete.id));
    expect(JSON.stringify([{ ...localStorage }, { ...sessionStorage }])).not.toMatch(/Sub 14|Femenino|Zeta|0042/);

    await user.click(submitButton());
    await screen.findByRole('heading', { level: 1, name: 'Temporada 2027' });

    const logged = spies.flatMap((spy) => spy.mock.calls).map((call) => call.map(String).join(' ')).join('\n');
    const stored = JSON.stringify([{ ...localStorage }, { ...sessionStorage }]);
    for (const secret of ['Lucía', 'Sintética', DNI, CUIL, 'Calle Falsa', 'Sub 14 reservada', 'Femenino', 'Zeta', '0042']) {
      expect(logged, `console: ${secret}`).not.toContain(secret);
      expect(stored, `storage: ${secret}`).not.toContain(secret);
    }
    const urls = backend.requests.filter((r) => !r.url.startsWith('/auth/')).map((r) => r.url);
    for (const secret of ['Sub 14', 'Femenino', 'Zeta', '0042', DNI, CUIL]) expect(urls.join(' ')).not.toContain(secret);
    expect(JSON.stringify(posts().map((r) => r.body))).not.toMatch(new RegExp(`${DNI}|${CUIL}|Calle Falsa|Lucía`));
    expect(JSON.stringify(queryClient.getQueryCache().getAll().map((q) => q.state.data))).not.toContain(DNI);
  });

  it('only the three reads of the form and the one POST leave the screen', async () => {
    const { athlete } = seed();
    backend.signIn('marcos@example.com');
    renderApp(alta(athlete.id));
    const user = userEvent.setup();
    await screen.findByRole('form', { name: 'Cargar datos deportivos' });
    await user.selectOptions(seasonSelect(), within(seasonSelect()).getByRole('option', { name: 'Temporada 2027' }));
    await user.click(submitButton());
    await screen.findByRole('heading', { level: 1, name: 'Temporada 2027' });

    const sent = backend.requests.filter((r) => !r.url.startsWith('/auth/')).map((r) => `${r.method} ${r.url.replace(athlete.id, ':id')}`);
    const allowed = new Set(['get /admin/deportistas/:id', 'get /admin/deportistas/:id/temporadas', 'get /admin/temporadas?page=0&size=100', 'post /admin/deportistas/:id/temporadas']);
    for (const request of sent) {
      const normalized = request.replace(/\/temporadas\/[^/\s]+$/, '/temporadas/:season');
      expect(allowed.has(normalized) || normalized === 'get /admin/deportistas/:id/temporadas/:season', request).toBe(true);
    }
    expect(sent.filter((request) => request.startsWith('post'))).toEqual(['post /admin/deportistas/:id/temporadas']);
  });
});
