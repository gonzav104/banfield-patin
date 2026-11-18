import { act, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, useNavigate } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { App } from '@/app/App';
import { AppProviders, createQueryClient } from '@/app/providers';
import { sessionKey } from '@/features/auth/session';
import type { FakeBackend } from '@/test/fakeBackend';
import { admin, createBackend, renderApp } from '@/test/render';
import { CUIL, DNI, REVISION, seedAthlete, seedMixedLinks } from './links-fixtures';

// MOCK backend. Real-backend evidence lives in e2e/vinculos-consulta.real.spec.ts.
let backend: FakeBackend;

beforeEach(() => {
  localStorage.clear();
  sessionStorage.clear();
  backend = createBackend();
  backend.addUser({ ...admin, id: 'u-admin-3', nombre: 'Otra', email: 'otra.admin@example.com', mfaEnrolado: true });
});
afterEach(() => vi.restoreAllMocks());

const athleteKey = (id: string) => ['admin', 'athlete-links', id];
const familyKey = (id: string) => ['admin', 'family-athletes', id];
const location = () => screen.getByTestId('location').textContent ?? '';
const settle = () => act(async () => { await new Promise((r) => setTimeout(r, 400)); });
const notFound = (code: string) => ({ status: 404, body: { codigo: code, mensaje: 'x' } });

/** The whole app plus a way to navigate programmatically from one resource to another. */
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

/** A second athlete with its own family and links, so a late answer about the first one is recognisable. */
function seedOther() {
  const athlete = backend.addAdminAthlete({ nombre: 'Eva', apellido: 'Otero', dni: '30222333' });
  const family = backend.addFamily('Familia Otero');
  backend.addAdminLink(family.id, athlete.id, { estado: 'ACTIVO', esPrincipal: true });
  return { athlete, family };
}

describe('links of an athlete: a change of athlete while a response is still on its way', () => {
  it('a late answer about the PREVIOUS athlete never reaches the screen of the next one nor the cache', async () => {
    const a = seedMixedLinks(backend);
    const b = seedOther();
    backend.signIn('marcos@example.com');
    const held = backend.hold('get', `/admin/deportistas/${a.athlete.id}/vinculos`);
    const { queryClient, go } = renderWithNavigation(`/admin/deportistas/${a.athlete.id}/vinculos`);
    await screen.findByText('Cargando los vínculos…');

    await go(`/admin/deportistas/${b.athlete.id}/vinculos`);
    expect(await screen.findByText('Eva Otero')).toBeInTheDocument();
    expect(await screen.findByRole('link', { name: 'Familia Otero' })).toBeInTheDocument();
    held.release();
    await settle();

    expect(screen.getByRole('link', { name: 'Familia Otero' })).toBeInTheDocument();
    expect(screen.queryByText('Familia Gómez')).not.toBeInTheDocument();
    expect(screen.queryByText('Lucía Sintética')).not.toBeInTheDocument();
    expect(queryClient.getQueryData(athleteKey(a.athlete.id))).toBeUndefined();
  });

  it('moving to the next athlete shows no data of the previous one while the new answer is pending', async () => {
    const a = seedMixedLinks(backend);
    const b = seedOther();
    backend.signIn('marcos@example.com');
    const { go } = renderWithNavigation(`/admin/deportistas/${a.athlete.id}/vinculos`);
    await screen.findByRole('link', { name: 'Familia Gómez' });

    const held = backend.hold('get', `/admin/deportistas/${b.athlete.id}/vinculos`);
    await go(`/admin/deportistas/${b.athlete.id}/vinculos`);
    await screen.findByText('Cargando los vínculos…');
    expect(screen.queryByText('Familia Gómez')).not.toBeInTheDocument();
    expect(screen.queryByText('Lucía Sintética')).not.toBeInTheDocument();
    held.release();
    expect(await screen.findByRole('link', { name: 'Familia Otero' })).toBeInTheDocument();
  });
});

describe('links of an athlete: a change of administrator', () => {
  it('an answer received AFTER the session changed to an identity that cannot see the athlete leaves nothing on screen or in the cache', async () => {
    const { athlete } = seedMixedLinks(backend);
    backend.signIn('marcos@example.com');
    const held = backend.hold('get', `/admin/deportistas/${athlete.id}/vinculos`);
    const { queryClient } = renderApp(`/admin/deportistas/${athlete.id}/vinculos`);
    await screen.findByText('Cargando los vínculos…');

    backend.signIn('otra.admin@example.com');
    backend.override('get', `/admin/deportistas/${athlete.id}`, notFound('DEPORTISTA_NO_ENCONTRADO'), { once: false });
    backend.override('get', `/admin/deportistas/${athlete.id}/vinculos`, notFound('DEPORTISTA_NO_ENCONTRADO'), { once: false });
    await act(async () => { await queryClient.invalidateQueries({ queryKey: sessionKey }); });
    await screen.findByRole('heading', { name: 'No encontramos este deportista' });
    held.release();
    await settle();

    expect(screen.queryByText('Familia Gómez')).not.toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: 'Vínculos activos' })).not.toBeInTheDocument();
    expect(queryClient.getQueryData(athleteKey(athlete.id))).toBeUndefined();
  });

  it('loaded links of one administrator are dropped from memory as soon as the session changes', async () => {
    const { athlete } = seedMixedLinks(backend);
    backend.signIn('marcos@example.com');
    const { queryClient } = renderApp(`/admin/deportistas/${athlete.id}/vinculos`);
    await screen.findByRole('link', { name: 'Familia Gómez' });
    expect(queryClient.getQueryData(athleteKey(athlete.id))).toBeDefined();

    backend.signIn('otra.admin@example.com');
    backend.override('get', `/admin/deportistas/${athlete.id}`, notFound('DEPORTISTA_NO_ENCONTRADO'), { once: false });
    backend.override('get', `/admin/deportistas/${athlete.id}/vinculos`, notFound('DEPORTISTA_NO_ENCONTRADO'), { once: false });
    await act(async () => { await queryClient.invalidateQueries({ queryKey: sessionKey }); });
    await screen.findByRole('heading', { name: 'No encontramos este deportista' });

    expect(queryClient.getQueryData(athleteKey(athlete.id))).toBeUndefined();
    expect(screen.queryByText('Familia Gómez')).not.toBeInTheDocument();
  });

  it('keeps the links only while a screen uses them', async () => {
    const { athlete } = seedMixedLinks(backend);
    backend.signIn('marcos@example.com');
    const { queryClient, go } = renderWithNavigation(`/admin/deportistas/${athlete.id}/vinculos`);
    await screen.findByRole('link', { name: 'Familia Gómez' });
    expect(queryClient.getQueryData(athleteKey(athlete.id))).toBeDefined();

    await go('/admin/deportistas');
    await screen.findByRole('heading', { name: 'Deportistas' });
    await settle();

    expect(queryClient.getQueryData(athleteKey(athlete.id))).toBeUndefined();
  });

  it('keeps the revision inside the private model of the query but never draws it, and keeps no personal field there', async () => {
    const { athlete } = seedMixedLinks(backend);
    backend.signIn('marcos@example.com');
    const { queryClient } = renderApp(`/admin/deportistas/${athlete.id}/vinculos`);
    await screen.findByRole('link', { name: 'Familia Gómez' });

    expect(queryClient.getQueryData<{ versionVinculos: string }>(athleteKey(athlete.id))?.versionVinculos).toBe(String(REVISION));
    expect(document.body.textContent).not.toContain(String(REVISION));
    const cached = JSON.stringify(queryClient.getQueryCache().getAll().map((q) => q.state.data));
    for (const secret of [DNI, CUIL, 'Calle Falsa', 'autorizadoEn']) expect(cached, secret).not.toContain(secret);
  });
});

describe('athletes of a family: a change of family or administrator while a response is still on its way', () => {
  it('a late answer about the PREVIOUS family never reaches the screen of the next one nor the cache', async () => {
    const { athlete: lucia } = seedMixedLinks(backend);
    const other = seedOther();
    const a = backend.addFamily('Familia Primera');
    backend.addAdminLink(a.id, lucia.id, { estado: 'REVOCADO' });
    backend.signIn('marcos@example.com');
    const held = backend.hold('get', `/admin/familias/${a.id}/deportistas`);
    const { queryClient, go } = renderWithNavigation(`/admin/familias/${a.id}`);
    await screen.findByText('Cargando los deportistas vinculados…');

    await go(`/admin/familias/${other.family.id}`);
    expect(await screen.findByRole('heading', { level: 1, name: 'Familia Otero' })).toBeInTheDocument();
    expect(await screen.findByRole('link', { name: 'Eva Otero' })).toBeInTheDocument();
    held.release();
    await settle();

    expect(screen.getByRole('link', { name: 'Eva Otero' })).toBeInTheDocument();
    expect(screen.queryByText('Lucía Sintética')).not.toBeInTheDocument();
    expect(queryClient.getQueryData(familyKey(a.id))).toBeUndefined();
  });

  it('moving to the next family shows no athlete of the previous one while the new answer is pending', async () => {
    seedMixedLinks(backend);
    const other = seedOther();
    const first = backend.addFamily('Familia Primera');
    const lucia = seedAthlete(backend, { nombre: 'Lola', apellido: 'Primera' });
    backend.addAdminLink(first.id, lucia.id, { estado: 'ACTIVO' });
    backend.signIn('marcos@example.com');
    const { go } = renderWithNavigation(`/admin/familias/${first.id}`);
    await screen.findByRole('link', { name: 'Lola Primera' });

    const held = backend.hold('get', `/admin/familias/${other.family.id}/deportistas`);
    await go(`/admin/familias/${other.family.id}`);
    await screen.findByRole('heading', { level: 1, name: 'Familia Otero' });
    await screen.findByText('Cargando los deportistas vinculados…');
    expect(screen.queryByText('Lola Primera')).not.toBeInTheDocument();
    held.release();
    expect(await screen.findByRole('link', { name: 'Eva Otero' })).toBeInTheDocument();
  });

  it('an answer received AFTER the session changed to an identity that cannot see the family leaves nothing on screen or in the cache', async () => {
    const family = backend.addFamily('Familia Gómez');
    const eva = backend.addAdminAthlete({ nombre: 'Eva', apellido: 'Gómez', dni: '30222333' });
    backend.addAdminLink(family.id, eva.id, { estado: 'ACTIVO' });
    backend.signIn('marcos@example.com');
    const held = backend.hold('get', `/admin/familias/${family.id}/deportistas`);
    const { queryClient } = renderApp(`/admin/familias/${family.id}`);
    await screen.findByText('Cargando los deportistas vinculados…');

    backend.signIn('otra.admin@example.com');
    backend.override('get', `/admin/familias/${family.id}`, notFound('FAMILIA_NO_ENCONTRADA'), { once: false });
    backend.override('get', `/admin/familias/${family.id}/deportistas`, notFound('FAMILIA_NO_ENCONTRADA'), { once: false });
    await act(async () => { await queryClient.invalidateQueries({ queryKey: sessionKey }); });
    await screen.findByRole('heading', { name: 'No encontramos esta familia' });
    held.release();
    await settle();

    expect(screen.queryByText('Eva Gómez')).not.toBeInTheDocument();
    expect(queryClient.getQueryData(familyKey(family.id))).toBeUndefined();
  });

  it('loaded athletes of one administrator are dropped from memory as soon as the session changes, and when the screen is left', async () => {
    const family = backend.addFamily('Familia Gómez');
    const eva = backend.addAdminAthlete({ nombre: 'Eva', apellido: 'Gómez', dni: '30222333' });
    backend.addAdminLink(family.id, eva.id, { estado: 'ACTIVO' });
    backend.signIn('marcos@example.com');
    const { queryClient, go } = renderWithNavigation(`/admin/familias/${family.id}`);
    await screen.findByRole('link', { name: 'Eva Gómez' });
    expect(queryClient.getQueryData(familyKey(family.id))).toBeDefined();

    await go('/admin/familias');
    await screen.findByRole('heading', { name: 'Familias' });
    await settle();
    expect(queryClient.getQueryData(familyKey(family.id))).toBeUndefined();

    await go(`/admin/familias/${family.id}`);
    await screen.findByRole('link', { name: 'Eva Gómez' });
    backend.signIn('otra.admin@example.com');
    backend.override('get', `/admin/familias/${family.id}`, notFound('FAMILIA_NO_ENCONTRADA'), { once: false });
    backend.override('get', `/admin/familias/${family.id}/deportistas`, notFound('FAMILIA_NO_ENCONTRADA'), { once: false });
    await act(async () => { await queryClient.invalidateQueries({ queryKey: sessionKey }); });
    await screen.findByRole('heading', { name: 'No encontramos esta familia' });
    expect(queryClient.getQueryData(familyKey(family.id))).toBeUndefined();
  });
});

describe('links: privacy', () => {
  it('walks athlete -> links -> family -> athlete with only identifiers in the address, nothing in storage, nothing in the console and no write', async () => {
    const { athlete, gomez } = seedMixedLinks(backend);
    const spies = (['log', 'info', 'warn', 'error', 'debug'] as const).map((method) => vi.spyOn(console, method).mockImplementation(() => undefined));
    backend.signIn('marcos@example.com');
    renderApp(`/admin/deportistas/${athlete.id}`);
    const user = userEvent.setup();
    const seen: string[] = [];

    await screen.findByRole('heading', { level: 1, name: 'Lucía Sintética' });
    seen.push(location());
    await user.click(screen.getByRole('link', { name: 'Ver familias vinculadas' }));
    await screen.findByRole('heading', { level: 2, name: 'Vínculos activos' });
    seen.push(location());
    await user.click(screen.getByRole('link', { name: 'Familia Gómez' }));
    await screen.findByRole('heading', { level: 3, name: 'Vínculos activos' });
    seen.push(location());
    await user.click(screen.getByRole('link', { name: 'Lucía Sintética' }));
    await screen.findByRole('heading', { level: 1, name: 'Lucía Sintética' });
    seen.push(location());

    expect(seen).toEqual([
      `/admin/deportistas/${athlete.id}`,
      `/admin/deportistas/${athlete.id}/vinculos`,
      `/admin/familias/${gomez.id}`,
      `/admin/deportistas/${athlete.id}`,
    ]);
    const stored = JSON.stringify([{ ...localStorage }, { ...sessionStorage }]);
    const logged = spies.flatMap((spy) => spy.mock.calls).map((call) => call.map(String).join(' ')).join('\n');
    for (const secret of ['Lucía', 'Sintética', 'Gómez', 'Pérez', 'Ruiz', DNI, CUIL, String(REVISION)]) {
      expect(stored, `storage: ${secret}`).not.toContain(secret);
      expect(logged, `console: ${secret}`).not.toContain(secret);
    }
    for (const address of seen) expect(address).not.toMatch(new RegExp(`${DNI}|${CUIL}|${REVISION}|Luc|Sint`));
    expect(backend.requests.filter((r) => r.method !== 'get' && !r.url.startsWith('/auth/'))).toEqual([]);
    expect(backend.writes()).toEqual([]);
  });

  it('every private query of these screens lives under the «admin» prefix, so the session code can drop it', async () => {
    const { athlete, gomez } = seedMixedLinks(backend);
    backend.signIn('marcos@example.com');
    const { queryClient, go } = renderWithNavigation(`/admin/deportistas/${athlete.id}/vinculos`);
    await screen.findByRole('link', { name: 'Familia Gómez' });
    await go(`/admin/familias/${gomez.id}`);
    await screen.findByRole('heading', { level: 3, name: 'Vínculos activos' });

    const keys = queryClient.getQueryCache().getAll().map((q) => q.queryKey);
    const linkKeys = keys.filter((key) => key.includes('athlete-links') || key.includes('family-athletes'));
    expect(linkKeys.length).toBeGreaterThan(0);
    for (const key of linkKeys) expect(key[0]).toBe('admin');
    for (const key of keys) expect(JSON.stringify(key)).not.toMatch(/Luc|Sint|Gómez|30111322|20301113227/);
  });

  it('draws the screen of an athlete from the name and the state only: the identification of the detail never reaches the page', async () => {
    const { athlete } = seedMixedLinks(backend);
    backend.signIn('marcos@example.com');
    renderApp(`/admin/deportistas/${athlete.id}/vinculos`);
    await screen.findByRole('link', { name: 'Familia Gómez' });
    expect(within(document.body).queryByText(new RegExp(`${DNI}|${CUIL}`))).not.toBeInTheDocument();
  });
});
