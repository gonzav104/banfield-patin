import { act, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, useLocation, useNavigate } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { App } from '@/app/App';
import { AppProviders, createQueryClient } from '@/app/providers';
import { sessionKey } from '@/features/auth/session';
import type { FakeBackend } from '@/test/fakeBackend';
import { admin, createBackend, renderApp } from '@/test/render';

// MOCK backend. Real-backend evidence lives in e2e/deportistas-datos-deportivos-edicion.real.spec.ts.
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
const edit = (athleteId: string, seasonId: string) => `/admin/deportistas/${athleteId}/temporadas/${seasonId}/editar`;
const puts = () => backend.requests.filter((r) => r.method === 'put' && /^\/admin\/deportistas\/[^/]+\/temporadas\/[^/]+$/.test(r.url));
const settle = () => act(async () => { await new Promise((r) => setTimeout(r, 400)); });
const field = (label: RegExp) => screen.getByLabelText(label);
const saveButton = () => screen.getByRole('button', { name: /^(Guardar cambios|Guardando…)$/ });
const reads = () => backend.requests.filter((r) => r.method === 'get' && /^\/admin\/(deportistas|temporadas)/.test(r.url)).length;

function seed() {
  const athlete = backend.addAdminAthlete({ nombre: 'Lucía', apellido: 'Sintética', dni: DNI, cuil: CUIL, domicilio: 'Calle Falsa 123' });
  const season = backend.addSeason(2027);
  const row = backend.addSportsRow(athlete.id, season.id, { categoria: 'Sub 14', rama: 'Femenino', divisional: 'A', numeroCorredor: '0007', estadoDeportivo: 'SUSPENDIDO', version: 3 });
  return { athlete, season, row };
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

async function replace(user: ReturnType<typeof userEvent.setup>, label: RegExp, value: string) {
  const input = field(label);
  await user.clear(input);
  if (value !== '') await user.type(input, value);
}

describe('edit sports data: a PUT that is still on its way when the session changes', () => {
  it('an answer that arrives AFTER the identity changed neither navigates nor leaves anything in the cache', async () => {
    const { athlete, season } = seed();
    backend.signIn('marcos@example.com');
    const { queryClient } = renderApp(edit(athlete.id, season.id));
    const user = userEvent.setup();
    await screen.findByRole('form', { name: 'Editar datos deportivos' });
    await replace(user, /^Categoría/, 'Sub 16 reservada');
    const held = backend.hold('put', `/admin/deportistas/${athlete.id}/temporadas/${season.id}`);
    await user.click(saveButton());
    await vi.waitFor(() => expect(puts()).toHaveLength(1));

    backend.signIn('otra.admin@example.com');
    const notFound = { status: 404, body: { codigo: 'DEPORTISTA_NO_ENCONTRADO', mensaje: 'x' } };
    backend.override('get', `/admin/deportistas/${athlete.id}`, notFound, { once: false });
    backend.override('get', `/admin/deportistas/${athlete.id}/temporadas/${season.id}`, notFound, { once: false });
    await act(async () => { await queryClient.invalidateQueries({ queryKey: sessionKey }); });
    await screen.findByRole('heading', { name: 'No encontramos este deportista' });
    held.release();
    await settle();

    expect(location()).toBe(edit(athlete.id, season.id)); // it never went to the detail
    expect(screen.queryByText('Cambios guardados.')).not.toBeInTheDocument();
    expect(screen.queryByText('Sub 16 reservada')).not.toBeInTheDocument();
    expect(queryClient.getQueryData(rowKey(athlete.id, season.id))).toBeUndefined();
    expect(queryClient.getQueryData(headerKey(athlete.id))).toBeUndefined();
  });

  it('the late answer of the previous identity does not even make the NEW identity reload what it is looking at', async () => {
    const { athlete, season } = seed();
    backend.signIn('marcos@example.com');
    const { queryClient } = renderApp(edit(athlete.id, season.id));
    const user = userEvent.setup();
    await screen.findByRole('form', { name: 'Editar datos deportivos' });
    await replace(user, /^Categoría/, 'Sub 16');
    const held = backend.hold('put', `/admin/deportistas/${athlete.id}/temporadas/${season.id}`);
    await user.click(saveButton());
    await vi.waitFor(() => expect(puts()).toHaveLength(1));

    // Another administrator who CAN see the row takes the session: its own screen loads from nothing.
    backend.signIn('otra.admin@example.com');
    await act(async () => { await queryClient.invalidateQueries({ queryKey: sessionKey }); });
    await screen.findByRole('form', { name: 'Editar datos deportivos' });
    const readsBefore = reads();
    held.release();
    await settle();

    expect(reads()).toBe(readsBefore); // the answer belongs to the previous identity: it refreshes nothing of the new one
    expect(location()).toBe(edit(athlete.id, season.id));
    expect(screen.queryByText('Cambios guardados.')).not.toBeInTheDocument();
    // The new identity starts from the SERVER's state: the PUT of the previous one was not cancelled, and its effect is simply what is there.
    expect(field(/^Categoría/)).toHaveValue('Sub 16');
    expect(backend.sportsRowVersion(athlete.id, season.id)).toBe('4');
  });

  it('the new identity starts with an EMPTY draft: nothing typed by the previous one survives the change of session', async () => {
    const { athlete, season } = seed();
    backend.signIn('marcos@example.com');
    const { queryClient } = renderApp(edit(athlete.id, season.id));
    const user = userEvent.setup();
    await screen.findByRole('form', { name: 'Editar datos deportivos' });
    await replace(user, /^Categoría/, 'Borrador reservado');

    backend.signIn('otra.admin@example.com');
    await act(async () => { await queryClient.invalidateQueries({ queryKey: sessionKey }); });
    await vi.waitFor(() => expect(field(/^Categoría/)).toHaveValue('Sub 14')); // the form of the new identity, from the server's state

    expect(saveButton()).toBeDisabled();
    expect(screen.queryByDisplayValue('Borrador reservado')).not.toBeInTheDocument();
  });
});

describe('edit sports data: a change of row or athlete while something is on its way', () => {
  it('an answer that arrives AFTER the administrator moved to another row never takes them to the first one', async () => {
    const { athlete, season } = seed();
    const other = backend.addSeason(2026);
    backend.addSportsRow(athlete.id, other.id, { categoria: 'Sub 10', version: 0 });
    backend.signIn('marcos@example.com');
    const { go } = renderWithNavigation(edit(athlete.id, season.id));
    const user = userEvent.setup();
    await screen.findByRole('form', { name: 'Editar datos deportivos' });
    await replace(user, /^Categoría/, 'Sub 16');
    const held = backend.hold('put', `/admin/deportistas/${athlete.id}/temporadas/${season.id}`);
    await user.click(saveButton());
    await vi.waitFor(() => expect(puts()).toHaveLength(1));

    await go(edit(athlete.id, other.id));
    await screen.findByRole('form', { name: 'Editar datos deportivos' });
    expect(field(/^Categoría/)).toHaveValue('Sub 10');
    held.release();
    await settle();

    expect(location()).toBe(edit(athlete.id, other.id));
    expect(screen.queryByText('Cambios guardados.')).not.toBeInTheDocument();
    expect(field(/^Categoría/)).toHaveValue('Sub 10'); // the draft of the first row did not travel
    expect(field(/^Categoría/)).toBeEnabled();
  });

  it('the draft and the revision of one row never reach the form of another', async () => {
    const { athlete, season } = seed();
    const other = backend.addSeason(2026);
    backend.addSportsRow(athlete.id, other.id, { categoria: 'Sub 10', version: 8 });
    backend.signIn('marcos@example.com');
    const { go } = renderWithNavigation(edit(athlete.id, season.id));
    const user = userEvent.setup();
    await screen.findByRole('form', { name: 'Editar datos deportivos' });
    await replace(user, /^Categoría/, 'Borrador de la primera');

    await go(edit(athlete.id, other.id));
    await screen.findByRole('form', { name: 'Editar datos deportivos' });
    await replace(user, /^Divisional/, 'Z');
    await user.click(saveButton());

    await vi.waitFor(() => expect(puts()).toHaveLength(1));
    expect(puts()[0]!.url).toBe(`/admin/deportistas/${athlete.id}/temporadas/${other.id}`);
    expect(puts()[0]!.body).toMatchObject({ categoria: 'Sub 10', divisional: 'Z', versionEsperada: '8' });
  });

  it('a verification that answers after the administrator changed row shows nothing of the first one', async () => {
    const { athlete, season } = seed();
    const other = backend.addSeason(2026);
    backend.addSportsRow(athlete.id, other.id, { categoria: 'Sub 10', version: 0 });
    backend.signIn('marcos@example.com');
    const { go } = renderWithNavigation(edit(athlete.id, season.id));
    const user = userEvent.setup();
    await screen.findByRole('form', { name: 'Editar datos deportivos' });
    backend.override('put', `/admin/deportistas/${athlete.id}/temporadas/${season.id}`, 'network');
    await replace(user, /^Categoría/, 'Sub 16');
    await user.click(saveButton());
    await screen.findByRole('button', { name: 'Verificar datos vigentes' });
    backend.changeSportsRowBehindTheScenes(athlete.id, season.id, { divisional: 'B' });
    const held = backend.hold('get', `/admin/deportistas/${athlete.id}/temporadas/${season.id}`);
    await user.click(screen.getByRole('button', { name: 'Verificar datos vigentes' }));
    await vi.waitFor(() => expect(backend.requests.filter((r) => r.method === 'get' && r.url === `/admin/deportistas/${athlete.id}/temporadas/${season.id}`)).toHaveLength(2));

    await go(edit(athlete.id, other.id));
    await screen.findByRole('form', { name: 'Editar datos deportivos' });
    held.release();
    await settle();

    expect(screen.queryByRole('group', { name: 'Verificación' })).not.toBeInTheDocument();
    expect(field(/^Categoría/)).toHaveValue('Sub 10');
    expect(screen.queryByText('Sub 16')).not.toBeInTheDocument();
  });
});

describe('edit sports data: privacy', () => {
  it('keeps the draft out of the address, the browser storage and the console, and never reads or sends the DNI or the CUIL', async () => {
    const { athlete, season } = seed();
    const spies = (['log', 'info', 'warn', 'error', 'debug'] as const).map((method) => vi.spyOn(console, method).mockImplementation(() => undefined));
    backend.signIn('marcos@example.com');
    const { queryClient } = renderApp(edit(athlete.id, season.id));
    const user = userEvent.setup();
    await screen.findByRole('form', { name: 'Editar datos deportivos' });
    await replace(user, /^Categoría/, 'Sub 99 reservada');
    await replace(user, /^Rama/, 'Zeta');
    await replace(user, /^Número de corredor/, '0042');

    expect(location()).toBe(edit(athlete.id, season.id));
    expect(JSON.stringify([{ ...localStorage }, { ...sessionStorage }])).not.toMatch(/Sub 99|Zeta|0042/);

    await user.click(saveButton());
    await screen.findByRole('heading', { level: 1, name: 'Temporada 2027' });

    const logged = spies.flatMap((spy) => spy.mock.calls).map((call) => call.map(String).join(' ')).join('\n');
    const stored = JSON.stringify([{ ...localStorage }, { ...sessionStorage }]);
    for (const secret of ['Lucía', 'Sintética', DNI, CUIL, 'Calle Falsa', 'Sub 99 reservada', 'Zeta', '0042']) {
      expect(logged, `console: ${secret}`).not.toContain(secret);
      expect(stored, `storage: ${secret}`).not.toContain(secret);
    }
    const urls = backend.requests.filter((r) => !r.url.startsWith('/auth/')).map((r) => r.url);
    for (const secret of ['Sub 99', 'Zeta', '0042', DNI, CUIL]) expect(urls.join(' ')).not.toContain(secret);
    expect(JSON.stringify(puts().map((r) => r.body))).not.toMatch(new RegExp(`${DNI}|${CUIL}|Calle Falsa|Lucía`));
    expect(JSON.stringify(queryClient.getQueryCache().getAll().map((q) => q.state.data))).not.toContain(DNI);
  });

  it('only the reads of the form, the one PUT and the detail after it leave the screen', async () => {
    const { athlete, season } = seed();
    backend.signIn('marcos@example.com');
    renderApp(edit(athlete.id, season.id));
    const user = userEvent.setup();
    await screen.findByRole('form', { name: 'Editar datos deportivos' });
    await replace(user, /^Categoría/, 'Sub 16');
    await user.click(saveButton());
    await screen.findByRole('heading', { level: 1, name: 'Temporada 2027' });

    const sent = backend.requests.filter((r) => !r.url.startsWith('/auth/')).map((r) => `${r.method} ${r.url.replace(athlete.id, ':id').replace(season.id, ':season')}`);
    const allowed = new Set(['get /admin/deportistas/:id', 'get /admin/deportistas/:id/temporadas/:season', 'put /admin/deportistas/:id/temporadas/:season']);
    for (const request of sent) expect(allowed.has(request), request).toBe(true);
    expect(sent.filter((request) => request.startsWith('put'))).toEqual(['put /admin/deportistas/:id/temporadas/:season']);
  });

  it('the edit screen only ever holds the name and the school state of the athlete, never the identification', async () => {
    const { athlete, season } = seed();
    backend.signIn('marcos@example.com');
    const { queryClient } = renderApp(edit(athlete.id, season.id));
    await screen.findByRole('form', { name: 'Editar datos deportivos' });

    expect(queryClient.getQueryData(headerKey(athlete.id))).toEqual({ id: athlete.id, nombre: 'Lucía', apellido: 'Sintética', activo: true });
    expect(within(document.body).queryByText(new RegExp(DNI))).not.toBeInTheDocument();
  });
});
