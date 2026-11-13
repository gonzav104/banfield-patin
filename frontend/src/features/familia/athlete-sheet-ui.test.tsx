import { act, cleanup, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { QueryClient } from '@tanstack/react-query';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { sessionKey } from '@/features/auth/session';
import type { FakeBackend, FakeCurrentSeason } from '@/test/fakeBackend';
import { admin, createBackend, family, renderApp } from '@/test/render';

// MOCK backend (src/test/fakeBackend.ts). The screen runs inside the REAL app: guards, layout, router, session and cache.
// Real-backend evidence for the sheet is reserved for the next unit (e2e).
let backend: FakeBackend;

const SEASON: FakeCurrentSeason = { anio: 2026, categoria: 'Sub 12', rama: 'Femenino', divisional: 'A', numeroCorredor: '007', estadoDeportivo: 'ACTIVO' };
const ghost = '00000000-0000-4000-8000-0000000000ff';
const sheetPath = (id: string) => `/familia/deportistas/${id}`;
const sheetUrl = (id: string) => `${sheetPath(id)}/ficha`;
const requestsTo = (url: string) => backend.requests.filter((r) => r.url === url).length;
const familyRequests = () => backend.requests.filter((r) => r.url.startsWith('/familia/')).length;
const meRequests = () => backend.requests.filter((r) => r.url === '/auth/me').length;
const main = () => screen.getByRole('main');

function own(over: Partial<Parameters<FakeBackend['addAthlete']>[0]> = {}) {
  const athlete = backend.addAthlete({ nombre: 'Ana', apellido: 'Paz', edad: 9, activo: true, vigente: SEASON, ...over });
  backend.linkAthlete('familia-1', athlete.id);
  return athlete;
}

/** The text of the <dd> that follows a <dt> inside a named region. */
function valueOf(regionName: string, term: string): string {
  const region = screen.getByRole('region', { name: regionName });
  return within(region).getByText(term, { selector: 'dt' }).nextElementSibling?.textContent ?? '';
}

async function silentIdentityChange(queryClient: QueryClient, email: string) {
  backend.signIn(email);
  await act(async () => {
    await queryClient.invalidateQueries({ queryKey: sessionKey });
  });
}

async function releaseAndSettle(held: { release: () => void }) {
  await act(async () => {
    held.release();
    await new Promise((resolve) => setTimeout(resolve, 30));
  });
}

beforeEach(() => {
  backend = createBackend();
  backend.addUser({ ...family, id: 'u-b', nombre: 'Rosa', email: 'otra@example.com', familiaId: 'familia-2' });
  backend.addUser({ ...family, id: 'u-sis', nombre: 'Julia', email: 'hermana@example.com', familiaId: 'familia-1' }); // same family as Lucía
  backend.addPortalFamily('familia-2', 'Familia López');
  backend.signIn(family.email);
});

afterEach(() => backend.clearOverrides());

describe('athlete sheet: what it shows', () => {
  it('shows the name, age, school status and the current season with every sports field', async () => {
    const athlete = own();
    renderApp(sheetPath(athlete.id));

    expect(await screen.findByRole('heading', { level: 1, name: 'Ana Paz' })).toBeInTheDocument();
    expect(valueOf('Datos del deportista', 'Edad')).toBe('9 años');
    expect(valueOf('Datos del deportista', 'Estado en la escuela')).toBe('Activo');
    expect(screen.getByRole('heading', { level: 2, name: 'Temporada 2026' })).toBeInTheDocument();
    expect(valueOf('Temporada 2026', 'Categoría')).toBe('Sub 12');
    expect(valueOf('Temporada 2026', 'Rama')).toBe('Femenino');
    expect(valueOf('Temporada 2026', 'Divisional')).toBe('A');
    expect(valueOf('Temporada 2026', 'Número de corredor')).toBe('007');
    expect(valueOf('Temporada 2026', 'Estado deportivo')).toBe('Activo');
  });

  it('keeps the runner number as text: 007 stays 007', async () => {
    const athlete = own({ vigente: { ...SEASON, numeroCorredor: '0012' } });
    renderApp(sheetPath(athlete.id));

    await screen.findByRole('heading', { level: 1, name: 'Ana Paz' });
    expect(valueOf('Temporada 2026', 'Número de corredor')).toBe('0012');
  });

  it.each([
    ['ACTIVO', 'Activo'],
    ['INACTIVO', 'Inactivo'],
    ['SUSPENDIDO', 'Suspendido'],
  ] as const)('shows the sports status %s as "%s", as text, with no cause attached', async (estadoDeportivo, label) => {
    const athlete = own({ vigente: { ...SEASON, estadoDeportivo } });
    renderApp(sheetPath(athlete.id));

    await screen.findByRole('heading', { level: 1, name: 'Ana Paz' });
    expect(valueOf('Temporada 2026', 'Estado deportivo')).toBe(label);
    expect(screen.getByRole('region', { name: 'Temporada 2026' }).textContent).not.toMatch(/motivo|causa|sanci|disciplin|porque|por qué|castigo/i);
  });

  it('keeps the two states apart and explains the difference: inactive in the school, active in the season', async () => {
    const athlete = own({ activo: false, vigente: { ...SEASON, estadoDeportivo: 'ACTIVO' } });
    renderApp(sheetPath(athlete.id));

    await screen.findByRole('heading', { level: 1, name: 'Ana Paz' });
    expect(valueOf('Datos del deportista', 'Estado en la escuela')).toBe('Inactivo');
    expect(valueOf('Temporada 2026', 'Estado deportivo')).toBe('Activo');
    expect(screen.getByText(/«Estado en la escuela» indica si el deportista figura activo o inactivo en la escuela/)).toBeInTheDocument();
    expect(screen.getByText(/«Estado deportivo» es su situación en la temporada vigente\. Son dos cosas distintas\./)).toBeInTheDocument();
  });

  it('says "Edad no informada" when the age is null and "No informado" for every empty sports field, blank text included', async () => {
    const athlete = own({ edad: null, vigente: { ...SEASON, categoria: null, rama: null, divisional: '   ', numeroCorredor: null } });
    renderApp(sheetPath(athlete.id));

    await screen.findByRole('heading', { level: 1, name: 'Ana Paz' });
    expect(valueOf('Datos del deportista', 'Edad')).toBe('Edad no informada');
    for (const term of ['Categoría', 'Rama', 'Divisional', 'Número de corredor']) expect(valueOf('Temporada 2026', term)).toBe('No informado');
    expect(valueOf('Temporada 2026', 'Estado deportivo')).toBe('Activo'); // the status is never "not reported"
  });

  it('with no current season it shows one neutral message: no reason, no invented values, no previous season', async () => {
    const athlete = own({ vigente: null });
    renderApp(sheetPath(athlete.id));

    await screen.findByRole('heading', { level: 1, name: 'Ana Paz' });
    const region = screen.getByRole('region', { name: 'Temporada vigente' });
    expect(region).toHaveTextContent('No hay datos de la temporada vigente para mostrar.');
    expect(region.textContent).not.toMatch(/porque|motivo|todavía|aún|cargad|no tiene|no hay una temporada|activa|2025|2026/i);
    expect(within(region).queryByRole('term')).not.toBeInTheDocument(); // nothing made up
    expect(screen.queryByRole('heading', { name: /^Temporada \d{4}$/ })).not.toBeInTheDocument();
  });

  it('shows nothing sensitive, even if a server over-shares: no DNI, CUIL, birth date, address, contact data or ids', async () => {
    const athlete = own({
      extra: { dni: '98765432', cuil: '27-98765432-1', fechaNacimiento: '2016-05-01', domicilio: 'Calle Falsa 123', telefonoContacto: '11-4444-0000', emailFederativo: 'secreto@sentinel.example', escuelaId: 'escuela-1', familiaId: 'familia-1', temporadaId: 'temporada-xyz' },
    });
    renderApp(sheetPath(athlete.id));

    await screen.findByRole('heading', { level: 1, name: 'Ana Paz' });
    expect(main().innerHTML).not.toMatch(/98765432|27-98765432|2016-05-01|Calle Falsa|4444|secreto@|escuela-1|familia-1|temporada-xyz|fechaNacimiento/);
    expect(main().textContent).not.toMatch(/DNI|CUIL|domicilio|nacimiento|teléfono|correo/i);
    expect(main().innerHTML).not.toContain(athlete.id); // the athlete id is in the URL, never printed on screen
  });

  it('offers no administrative links or actions: only the way back', async () => {
    const athlete = own();
    renderApp(sheetPath(athlete.id));

    await screen.findByRole('heading', { level: 1, name: 'Ana Paz' });
    expect(within(main()).getAllByRole('link').map((link) => link.textContent?.trim())).toEqual(['← Volver a deportistas']);
    expect(within(main()).queryAllByRole('button')).toHaveLength(0);
  });
});

describe('athlete sheet: navigation and access', () => {
  it('opens from each athlete of the list, with a link that has a readable name, and keeps "Deportistas" current', async () => {
    const ana = own();
    own({ nombre: 'Cata', apellido: 'Gómez', edad: 5, vigente: null });
    const user = userEvent.setup();
    renderApp('/familia/deportistas');

    const link = await screen.findByRole('link', { name: /^Ana Paz 9 años Estado:\s*Activo$/ });
    expect(link).toHaveAttribute('href', sheetPath(ana.id));
    expect(screen.getByRole('link', { name: /^Cata Gómez 5 años Estado:\s*Activo$/ })).toBeInTheDocument();

    await user.click(link);

    expect(await screen.findByRole('heading', { level: 1, name: 'Ana Paz' })).toBeInTheDocument();
    expect(screen.getByTestId('location')).toHaveTextContent(sheetPath(ana.id));
    expect(within(screen.getByRole('navigation', { name: 'Principal' })).getByRole('link', { name: 'Deportistas' })).toHaveAttribute('aria-current', 'page');
    expect(requestsTo(sheetUrl(ana.id))).toBe(1);
  });

  it('goes back to the list inside the app, without asking for the list again', async () => {
    const ana = own();
    const user = userEvent.setup();
    renderApp('/familia/deportistas');
    await user.click(await screen.findByRole('link', { name: /^Ana Paz/ }));
    await screen.findByRole('heading', { level: 1, name: 'Ana Paz' });

    await user.click(screen.getByRole('link', { name: /Volver a deportistas/ }));

    expect(await screen.findByRole('heading', { level: 1, name: 'Deportistas' })).toBeInTheDocument();
    expect(screen.getByTestId('location')).toHaveTextContent('/familia/deportistas');
    expect(screen.getByRole('link', { name: /^Ana Paz/ })).toHaveAttribute('href', sheetPath(ana.id));
    expect(requestsTo('/familia/deportistas?page=0&size=10')).toBe(1); // the list was still fresh: client-side navigation
  });

  it('opens from the dashboard preview too', async () => {
    const ana = own();
    const user = userEvent.setup();
    renderApp('/familia');

    await user.click(await screen.findByRole('link', { name: /^Ana Paz/ }));

    expect(await screen.findByRole('heading', { level: 1, name: 'Ana Paz' })).toBeInTheDocument();
    expect(screen.getByTestId('location')).toHaveTextContent(sheetPath(ana.id));
  });

  it('works by direct access and reload: one request, only to the sheet, never the 15-field detail or the seasons', async () => {
    const ana = own();
    renderApp(sheetPath(ana.id));
    expect(await screen.findByRole('heading', { level: 1, name: 'Ana Paz' })).toBeInTheDocument();
    expect(screen.getByTestId('location')).toHaveTextContent(sheetPath(ana.id));
    cleanup(); // a reload: nothing survives in memory

    renderApp(sheetPath(ana.id));
    expect(await screen.findByRole('heading', { level: 1, name: 'Ana Paz' })).toBeInTheDocument();

    const urls = backend.requests.filter((r) => r.url.startsWith('/familia/')).map((r) => r.url);
    expect(urls).toEqual([sheetUrl(ana.id), sheetUrl(ana.id)]); // one per load, nothing else
  });

  it('keeps the protected routes: without a session it goes to login, and an administrator never reaches the data', async () => {
    const ana = own();
    backend.expireSession();
    renderApp(sheetPath(ana.id));
    expect(await screen.findByRole('heading', { name: 'Ingresá a tu portal' })).toBeInTheDocument();
    expect(screen.getByTestId('location')).toHaveTextContent('/login');
    cleanup();

    backend.signIn(admin.email);
    renderApp(sheetPath(ana.id));
    await waitFor(() => expect(meRequests()).toBeGreaterThan(1));
    await act(async () => {
      await new Promise((resolve) => setTimeout(resolve, 30));
    });
    expect(screen.queryByRole('heading', { name: 'Ana Paz' })).not.toBeInTheDocument();
    expect(familyRequests()).toBe(0);
  });
});

describe('athlete sheet: states', () => {
  it('shows a loading state with its title while the sheet loads', async () => {
    const ana = own();
    const held = backend.hold('get', sheetUrl(ana.id));
    renderApp(sheetPath(ana.id));

    expect(await screen.findByText('Cargando la ficha…')).toBeInTheDocument();
    expect(screen.getByRole('heading', { level: 1, name: 'Ficha del deportista' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /Volver a deportistas/ })).toBeInTheDocument();

    await releaseAndSettle(held);
    expect(await screen.findByRole('heading', { level: 1, name: 'Ana Paz' })).toBeInTheDocument();
  });

  it('an id that is not a UUID gets the not-found screen and nothing is requested', async () => {
    renderApp(sheetPath('no-es-uuid'));

    expect(await screen.findByText('No encontramos este deportista')).toBeInTheDocument();
    expect(screen.getByText(/Revisá el enlace o volvé al listado/)).toBeInTheDocument();
    expect(familyRequests()).toBe(0);
  });

  it('a foreign, a non-existent and a revoked athlete get the SAME screen, which names nobody', async () => {
    const foreign = backend.addAthlete({ nombre: 'Beto', apellido: 'López', edad: 7, activo: true, vigente: SEASON });
    backend.linkAthlete('familia-2', foreign.id);
    const revoked = own({ nombre: 'Rev', apellido: 'Oculto' });
    backend.revokeLink('familia-1', revoked.id);

    const screens: string[] = [];
    for (const id of [foreign.id, ghost, revoked.id, 'no-es-uuid']) {
      renderApp(sheetPath(id));
      expect(await screen.findByText('No encontramos este deportista')).toBeInTheDocument();
      screens.push(main().textContent ?? '');
      cleanup();
    }

    expect(new Set(screens).size).toBe(1); // identical text for every cause
    expect(screens[0]).not.toMatch(/López|Beto|\bRev\b|Oculto/); // it names nobody (the word "familia" is only the generic copy)
  });

  it('a temporary failure (503) keeps the session, offers a retry and recovers', async () => {
    const ana = own();
    backend.override('get', sheetUrl(ana.id), { status: 503, body: { codigo: 'SERVICIO_NO_DISPONIBLE', mensaje: 'x' } }, { once: true });
    const user = userEvent.setup();
    renderApp(sheetPath(ana.id));

    expect(await screen.findByText(/El servicio no está disponible/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Cerrar sesión' })).toBeInTheDocument(); // still signed in
    await user.click(screen.getByRole('button', { name: 'Reintentar' }));

    expect(await screen.findByRole('heading', { level: 1, name: 'Ana Paz' })).toBeInTheDocument();
  });

  it('a network failure and a permission error (403) are explained, without closing the session', async () => {
    const ana = own();
    backend.override('get', sheetUrl(ana.id), 'network', { once: true });
    renderApp(sheetPath(ana.id));
    expect(await screen.findByText(/No pudimos conectar con el servidor/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Reintentar' })).toBeInTheDocument();
    cleanup();

    backend.override('get', sheetUrl(ana.id), { status: 403, body: { codigo: 'ACCESO_DENEGADO', mensaje: 'x' } });
    renderApp(sheetPath(ana.id));
    expect(await screen.findByText('No tenés permiso para ver esta información.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Cerrar sesión' })).toBeInTheDocument();
  });

  it('an expired session (401) goes to login and shows no athlete data', async () => {
    const ana = own();
    backend.override('get', sheetUrl(ana.id), { status: 401, body: { codigo: 'NO_AUTENTICADO', mensaje: 'x' } });
    renderApp(sheetPath(ana.id));

    expect(await screen.findByRole('heading', { name: 'Ingresá a tu portal' })).toBeInTheDocument();
    expect(screen.getByTestId('location')).toHaveTextContent('/login');
    expect(document.body.textContent).not.toMatch(/Ana Paz|Sub 12/);
  });

  it('an account without a family gets the same not-found screen and requests nothing', async () => {
    backend.addUser({ ...family, id: 'u-nofam', email: 'sinfamilia@example.com', familiaId: null });
    backend.signIn('sinfamilia@example.com');
    renderApp(sheetPath(ghost));

    expect(await screen.findByText('No encontramos este deportista')).toBeInTheDocument();
    expect(familyRequests()).toBe(0);
  });
});

describe('athlete sheet: isolation with the screen mounted', () => {
  it('family A -> family B: nothing of A while B loads, then the same not-found screen', async () => {
    const ana = own();
    const { queryClient } = renderApp(sheetPath(ana.id));
    await screen.findByRole('heading', { level: 1, name: 'Ana Paz' });

    const held = backend.hold('get', sheetUrl(ana.id));
    await silentIdentityChange(queryClient, 'otra@example.com');
    await waitFor(() => expect(requestsTo(sheetUrl(ana.id))).toBe(2));

    expect(screen.getByText('Cargando la ficha…')).toBeInTheDocument(); // the transitional render
    expect(document.body.textContent).not.toMatch(/Ana Paz|Sub 12|Femenino|007/);
    await releaseAndSettle(held);
    expect(await screen.findByText('No encontramos este deportista')).toBeInTheDocument();
    expect(document.body.textContent).not.toMatch(/Ana|Sub 12|007/);
  });

  it('another user of the SAME family: the previous user data is not reused, the screen asks again', async () => {
    const ana = own();
    const { queryClient } = renderApp(sheetPath(ana.id));
    await screen.findByRole('heading', { level: 1, name: 'Ana Paz' });

    const held = backend.hold('get', sheetUrl(ana.id));
    await silentIdentityChange(queryClient, 'hermana@example.com');
    await waitFor(() => expect(requestsTo(sheetUrl(ana.id))).toBe(2));

    expect(screen.getByText('Cargando la ficha…')).toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: 'Ana Paz' })).not.toBeInTheDocument();
    await releaseAndSettle(held);
    expect(await screen.findByRole('heading', { level: 1, name: 'Ana Paz' })).toBeInTheDocument();
  });

  it('a late response of the previous identity never lands on the screen of the new one', async () => {
    const ana = own();
    const stale = backend.hold('get', sheetUrl(ana.id)); // A's request: answered as A, delivered late
    const { queryClient } = renderApp(sheetPath(ana.id));
    await waitFor(() => expect(requestsTo(sheetUrl(ana.id))).toBe(1));

    await silentIdentityChange(queryClient, 'otra@example.com');
    expect(await screen.findByText('No encontramos este deportista')).toBeInTheDocument();
    await releaseAndSettle(stale);

    expect(screen.getByText('No encontramos este deportista')).toBeInTheDocument();
    expect(document.body.textContent).not.toMatch(/Ana Paz|Sub 12/);
  });

  it('a late response of the previous USER of the same family shows the newer data, never the old', async () => {
    const ana = own();
    const stale = backend.hold('get', sheetUrl(ana.id)); // snapshot: Sub 12
    const { queryClient } = renderApp(sheetPath(ana.id));
    await waitFor(() => expect(requestsTo(sheetUrl(ana.id))).toBe(1));
    ana.vigente = { ...SEASON, categoria: 'Sub 14' }; // the school changed the data meanwhile

    await silentIdentityChange(queryClient, 'hermana@example.com');
    await screen.findByRole('heading', { level: 1, name: 'Ana Paz' });
    expect(valueOf('Temporada 2026', 'Categoría')).toBe('Sub 14');
    await releaseAndSettle(stale);

    expect(valueOf('Temporada 2026', 'Categoría')).toBe('Sub 14');
    expect(document.body.textContent).not.toContain('Sub 12');
  });

  it('quick route changes: list -> Ana (pending) -> back -> Cata: a late answer for Ana never replaces Cata', async () => {
    const ana = own();
    own({ nombre: 'Cata', apellido: 'Gómez', edad: 5, vigente: { ...SEASON, categoria: 'Sub 8' } });
    const stale = backend.hold('get', sheetUrl(ana.id));
    const user = userEvent.setup();
    renderApp('/familia/deportistas');

    await user.click(await screen.findByRole('link', { name: /^Ana Paz/ }));
    expect(await screen.findByText('Cargando la ficha…')).toBeInTheDocument();
    await user.click(screen.getByRole('link', { name: /Volver a deportistas/ }));
    await user.click(await screen.findByRole('link', { name: /^Cata Gómez/ }));
    expect(await screen.findByRole('heading', { level: 1, name: 'Cata Gómez' })).toBeInTheDocument();

    await releaseAndSettle(stale);

    expect(screen.getByRole('heading', { level: 1, name: 'Cata Gómez' })).toBeInTheDocument();
    expect(valueOf('Temporada 2026', 'Categoría')).toBe('Sub 8');
    expect(document.body.textContent).not.toContain('Ana Paz');
  });

  it('shows another athlete cleanly after going back and forward between two of the same family', async () => {
    const ana = own();
    const cata = own({ nombre: 'Cata', apellido: 'Gómez', edad: null, vigente: null });
    const user = userEvent.setup();
    renderApp('/familia/deportistas');

    await user.click(await screen.findByRole('link', { name: /^Ana Paz/ }));
    await screen.findByRole('heading', { level: 1, name: 'Ana Paz' });
    await user.click(screen.getByRole('link', { name: /Volver a deportistas/ }));
    await user.click(await screen.findByRole('link', { name: /^Cata Gómez/ }));
    await screen.findByRole('heading', { level: 1, name: 'Cata Gómez' });

    expect(valueOf('Datos del deportista', 'Edad')).toBe('Edad no informada'); // nothing of Ana (9 años)
    expect(screen.getByRole('region', { name: 'Temporada vigente' })).toBeInTheDocument(); // not Ana's Temporada 2026
    expect(screen.queryByText('Sub 12')).not.toBeInTheDocument();
    expect(requestsTo(sheetUrl(ana.id))).toBe(1);
    expect(requestsTo(sheetUrl(cata.id))).toBe(1);
  });

  it('drops the private cache of the previous identity, keeps the session, does not loop on /me and writes nothing to storage', async () => {
    const ana = own();
    const { queryClient } = renderApp(sheetPath(ana.id));
    await screen.findByRole('heading', { level: 1, name: 'Ana Paz' });
    const meBefore = meRequests();

    await silentIdentityChange(queryClient, 'otra@example.com');
    await screen.findByText('No encontramos este deportista');

    const privateQueries = queryClient.getQueryCache().getAll().filter((q) => q.queryKey[0] === 'familia');
    expect(privateQueries.length).toBeGreaterThan(0);
    expect(privateQueries.every((q) => String(q.queryKey[1]).startsWith('u-b:'))).toBe(true);
    expect(JSON.stringify(privateQueries.map((q) => q.state.data))).not.toMatch(/Ana|Sub 12/);
    expect(queryClient.getQueryData(sessionKey)).toMatchObject({ id: 'u-b' });
    await act(async () => {
      await new Promise((resolve) => setTimeout(resolve, 50));
    });
    expect(meRequests()).toBe(meBefore + 1);
    expect({ local: Object.keys(localStorage), session: Object.keys(sessionStorage) }).toEqual({ local: [], session: [] });
  });
});

describe('athlete sheet: accessibility', () => {
  it('has one level-1 title, titled sections, a main landmark and definition lists with their terms', async () => {
    const ana = own();
    renderApp(sheetPath(ana.id));
    await screen.findByRole('heading', { level: 1, name: 'Ana Paz' });

    expect(screen.getAllByRole('heading', { level: 1 })).toHaveLength(1);
    expect(screen.getAllByRole('heading', { level: 2 }).map((h) => h.textContent)).toEqual(['Datos del deportista', 'Temporada 2026']);
    expect(screen.getAllByRole('region').map((r) => r.getAttribute('aria-labelledby'))).toEqual(['athlete-data-title', 'season-title']);
    expect(within(screen.getByRole('region', { name: 'Temporada 2026' })).getAllByRole('term').map((t) => t.textContent)).toEqual([
      'Categoría', 'Rama', 'Divisional', 'Número de corredor', 'Estado deportivo',
    ]);
    expect(within(screen.getByRole('region', { name: 'Temporada 2026' })).getAllByRole('definition')).toHaveLength(5);
  });

  it('moves the focus to the title when the sheet is ready, and the way back is one Shift+Tab away', async () => {
    const ana = own();
    const user = userEvent.setup();
    renderApp(sheetPath(ana.id));

    const title = await screen.findByRole('heading', { level: 1, name: 'Ana Paz' });
    await waitFor(() => expect(title).toHaveFocus());
    await user.tab({ shift: true });

    expect(screen.getByRole('link', { name: /Volver a deportistas/ })).toHaveFocus();
    await user.keyboard('{Enter}');
    expect(await screen.findByRole('heading', { level: 1, name: 'Deportistas' })).toBeInTheDocument(); // keyboard-only navigation works
  });

  it('every status is text, and the list links reach the sheet by keyboard', async () => {
    const ana = own({ activo: false, vigente: { ...SEASON, estadoDeportivo: 'SUSPENDIDO' } });
    const user = userEvent.setup();
    renderApp('/familia/deportistas');

    const link = await screen.findByRole('link', { name: /^Ana Paz 9 años Estado:\s*Inactivo$/ });
    link.focus();
    await user.keyboard('{Enter}');

    expect(await screen.findByRole('heading', { level: 1, name: 'Ana Paz' })).toBeInTheDocument();
    expect(valueOf('Datos del deportista', 'Estado en la escuela')).toBe('Inactivo');
    expect(valueOf('Temporada 2026', 'Estado deportivo')).toBe('Suspendido');
    expect(screen.getByTestId('location')).toHaveTextContent(sheetPath(ana.id));
  });
});
