import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it } from 'vitest';
import type { FakeBackend } from '@/test/fakeBackend';
import { createBackend, renderApp } from '@/test/render';

// MOCK backend (see src/test/fakeBackend.ts, which mirrors GET /api/admin/temporadas and GET /api/admin/deportistas/{id}/temporadas).
// Real-backend evidence lives in e2e/deportistas-datos-deportivos.real.spec.ts.
let backend: FakeBackend;

beforeEach(() => {
  localStorage.clear();
  sessionStorage.clear();
  backend = createBackend();
});

const DNI = '30111322';
const CUIL = '20301113227';
const location = () => screen.getByTestId('location').textContent ?? '';
const gets = (pattern: RegExp) => backend.requests.filter((r) => r.method === 'get' && pattern.test(r.url));
const historyGets = () => gets(/^\/admin\/deportistas\/[^/?]+\/temporadas$/);
const seasonGets = () => gets(/^\/admin\/temporadas(\?|$)/);
const nonGets = () => backend.requests.filter((r) => r.method !== 'get' && !r.url.startsWith('/auth/'));

function seedAthlete(over: { activo?: boolean } = {}) {
  return backend.addAdminAthlete({ nombre: 'Lucía', apellido: 'Sintética', dni: DNI, cuil: CUIL, domicilio: 'Calle Falsa 123', ...over });
}

function open(path: string) {
  backend.signIn('marcos@example.com');
  return renderApp(path);
}

describe('sports history: what the administrator sees', () => {
  it('lists the seasons newest first, whatever order the server used', async () => {
    const athlete = seedAthlete();
    const t26 = backend.addSeason(2026);
    const t28 = backend.addSeason(2028);
    const t27 = backend.addSeason(2027);
    backend.addSportsRow(athlete.id, t26.id, { categoria: 'Sub 10' });
    backend.addSportsRow(athlete.id, t28.id, { categoria: 'Sub 14' });
    backend.addSportsRow(athlete.id, t27.id, { categoria: 'Sub 12' });
    // The backend sends newest first; the screen must not depend on it.
    backend.override('get', `/admin/deportistas/${athlete.id}/temporadas`, {
      status: 200,
      body: [
        { id: 'r1', deportistaId: athlete.id, temporadaId: t26.id, anio: 2026, categoria: 'Sub 10', rama: null, divisional: null, numeroCorredor: null, estadoDeportivo: 'ACTIVO', version: '0' },
        { id: 'r2', deportistaId: athlete.id, temporadaId: t28.id, anio: 2028, categoria: 'Sub 14', rama: null, divisional: null, numeroCorredor: null, estadoDeportivo: 'ACTIVO', version: '0' },
        { id: 'r3', deportistaId: athlete.id, temporadaId: t27.id, anio: 2027, categoria: 'Sub 12', rama: null, divisional: null, numeroCorredor: null, estadoDeportivo: 'ACTIVO', version: '0' },
      ],
    });
    open(`/admin/deportistas/${athlete.id}/temporadas`);

    const list = await screen.findByRole('list', { name: 'Historial deportivo' });
    const items = within(list).getAllByRole('listitem');
    expect(items.map((i) => within(i).getByRole('heading', { level: 2 }).textContent)).toEqual(['Temporada 2028', 'Temporada 2027', 'Temporada 2026']);
  });

  it('shows the year, the annual sports status and every sports field, with «No informado» for the missing ones', async () => {
    const athlete = seedAthlete();
    const t27 = backend.addSeason(2027);
    const t26 = backend.addSeason(2026);
    backend.addSportsRow(athlete.id, t27.id, { categoria: 'Sub 14', rama: 'Femenino', divisional: 'A', numeroCorredor: '007', estadoDeportivo: 'SUSPENDIDO' });
    backend.addSportsRow(athlete.id, t26.id, {});
    open(`/admin/deportistas/${athlete.id}/temporadas`);

    const [first, second] = within(await screen.findByRole('list', { name: 'Historial deportivo' })).getAllByRole('listitem') as [HTMLElement, HTMLElement];
    expect(within(first).getByText('Estado deportivo de 2027:')).toBeInTheDocument();
    expect(within(first).getByText('Suspendido')).toBeInTheDocument();
    expect(within(first).getByText('Sub 14')).toBeInTheDocument();
    expect(within(first).getByText('Femenino')).toBeInTheDocument();
    expect(within(first).getByText('A')).toBeInTheDocument();
    expect(within(first).getByText('007')).toBeInTheDocument();
    expect(within(second).getByText('Estado deportivo de 2026:')).toBeInTheDocument();
    expect(within(second).getByText('Activo')).toBeInTheDocument();
    expect(within(second).getAllByText('No informado')).toHaveLength(4);
  });

  it('tells the annual sports status apart from the athlete state in the school', async () => {
    const athlete = seedAthlete({ activo: false });
    const t27 = backend.addSeason(2027);
    backend.addSportsRow(athlete.id, t27.id, { estadoDeportivo: 'ACTIVO' });
    open(`/admin/deportistas/${athlete.id}/temporadas`);

    await screen.findByRole('list', { name: 'Historial deportivo' });
    // Inactive in the school, active in the season: two different planes, each with its own label.
    expect(screen.getByText('Estado en la escuela:').parentElement).toHaveTextContent('Inactivo');
    expect(screen.getByText('Estado deportivo de 2027:').parentElement).toHaveTextContent('Activo');
    expect(screen.getByText(/«Estado en la escuela» indica si el deportista figura activo o inactivo en la escuela\. «Estado deportivo» es su situación en cada temporada\./)).toBeInTheDocument();
  });

  it('names the athlete and never shows the DNI, the CUIL, the address or the revision', async () => {
    const athlete = seedAthlete();
    const t27 = backend.addSeason(2027);
    backend.addSportsRow(athlete.id, t27.id, { categoria: 'Sub 14', version: 4242 });
    open(`/admin/deportistas/${athlete.id}/temporadas`);

    await screen.findByRole('list', { name: 'Historial deportivo' });
    expect(screen.getByText('Lucía Sintética')).toBeInTheDocument();
    const text = document.body.textContent ?? '';
    for (const secret of [DNI, CUIL, 'Calle Falsa', '4242']) expect(text).not.toContain(secret);
  });

  it('opens the season detail from its own link and goes back to the athlete detail', async () => {
    const athlete = seedAthlete();
    const t27 = backend.addSeason(2027);
    backend.addSportsRow(athlete.id, t27.id, { categoria: 'Sub 14' });
    open(`/admin/deportistas/${athlete.id}/temporadas`);
    const user = userEvent.setup();

    await user.click(await screen.findByRole('link', { name: 'Ver detalle de la temporada 2027' }));
    expect(location()).toBe(`/admin/deportistas/${athlete.id}/temporadas/${t27.id}`);
    expect(await screen.findByRole('heading', { level: 1, name: 'Temporada 2027' })).toBeInTheDocument();
    await user.click(screen.getByRole('link', { name: 'Volver al historial deportivo' }));
    await screen.findByRole('list', { name: 'Historial deportivo' });
    await user.click(screen.getByRole('link', { name: 'Volver al detalle del deportista' }));
    expect(location()).toBe(`/admin/deportistas/${athlete.id}`);
  });

  it('offers the way to LOAD data (U10-B) but no way to edit it, and sends only reads', async () => {
    const athlete = seedAthlete();
    const t27 = backend.addSeason(2027);
    backend.addSportsRow(athlete.id, t27.id, {});
    open(`/admin/deportistas/${athlete.id}/temporadas`);

    await screen.findByRole('list', { name: 'Historial deportivo' });
    const main = within(screen.getByRole('main'));
    expect(main.queryByRole('button')).not.toBeInTheDocument();
    expect(main.getByRole('link', { name: 'Cargar datos de una temporada' })).toHaveAttribute('href', `/admin/deportistas/${athlete.id}/temporadas/nueva`);
    expect(main.queryByRole('link', { name: /agregar|crear|editar|activar/i })).not.toBeInTheDocument();
    expect(nonGets()).toEqual([]);
  });
});

describe('sports history: empty states', () => {
  it('says there are no seasons at all, without a link to create one', async () => {
    const athlete = seedAthlete();
    open(`/admin/deportistas/${athlete.id}/temporadas`);

    expect(await screen.findByRole('heading', { name: 'Todavía no hay temporadas' })).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: /crear|nueva|activar|temporada/i })).not.toBeInTheDocument();
    expect(screen.queryByRole('list', { name: 'Historial deportivo' })).not.toBeInTheDocument();
  });

  it('says the athlete has no sports data when the school does have seasons', async () => {
    const athlete = seedAthlete();
    backend.addSeason(2026);
    backend.addSeason(2027, true);
    open(`/admin/deportistas/${athlete.id}/temporadas`);

    expect(await screen.findByRole('heading', { name: 'Este deportista todavía no tiene datos deportivos' })).toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: 'Todavía no hay temporadas' })).not.toBeInTheDocument();
  });

  it('decides «there are seasons» from the total of the page, not from how many came in it', async () => {
    const athlete = seedAthlete();
    // The backend caps a page at 100: a school can have more seasons than one page carries.
    backend.override('get', '/admin/temporadas?page=0&size=1', { status: 200, body: { contenido: [], pagina: 0, tamanio: 1, totalElementos: 5 } });
    open(`/admin/deportistas/${athlete.id}/temporadas`);

    expect(await screen.findByRole('heading', { name: 'Este deportista todavía no tiene datos deportivos' })).toBeInTheDocument();
    expect(seasonGets().map((r) => r.url)).toEqual(['/admin/temporadas?page=0&size=1']);
  });

  it('asks for the seasons ONLY when the history is empty', async () => {
    const athlete = seedAthlete();
    const t27 = backend.addSeason(2027);
    backend.addSportsRow(athlete.id, t27.id, {});
    open(`/admin/deportistas/${athlete.id}/temporadas`);

    await screen.findByRole('list', { name: 'Historial deportivo' });
    expect(seasonGets()).toHaveLength(0);
  });

  it('does not claim there are no seasons when they could not be checked, and lets the administrator retry', async () => {
    const athlete = seedAthlete();
    backend.addSeason(2027);
    backend.override('get', '/admin/temporadas?page=0&size=1', { status: 500, body: { codigo: 'ERROR_INTERNO' } }, { once: true });
    open(`/admin/deportistas/${athlete.id}/temporadas`);
    const user = userEvent.setup();

    expect(await screen.findByText('Ocurrió un problema en el servidor. Intentá de nuevo en unos minutos.')).toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: 'Todavía no hay temporadas' })).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Reintentar' }));
    expect(await screen.findByRole('heading', { name: 'Este deportista todavía no tiene datos deportivos' })).toBeInTheDocument();
  });
});

describe('sports history: loading and errors', () => {
  it('shows a loading status while the history is in flight', async () => {
    const athlete = seedAthlete();
    const held = backend.hold('get', `/admin/deportistas/${athlete.id}/temporadas`);
    open(`/admin/deportistas/${athlete.id}/temporadas`);

    expect(await screen.findByText('Cargando los datos deportivos…')).toHaveAttribute('role', 'status');
    held.release();
    await screen.findByRole('heading', { name: 'Todavía no hay temporadas' });
  });

  it.each([
    ['network', 'network' as const, 'No pudimos conectar con el servidor. Revisá tu conexión e intentá de nuevo.'],
    ['503', { status: 503, body: { codigo: 'NO_DISPONIBLE' } }, 'El servicio no está disponible por el momento. Intentá de nuevo en unos instantes.'],
    ['500', { status: 500, body: { codigo: 'ERROR_INTERNO' } }, 'Ocurrió un problema en el servidor. Intentá de nuevo en unos minutos.'],
    ['an unreadable 200', { status: 200, body: { basura: true } }, 'Ocurrió un problema en el servidor. Intentá de nuevo en unos minutos.'],
    ['403', { status: 403, body: { codigo: 'ACCESO_DENEGADO' } }, 'No tenés permiso para ver los datos deportivos.'],
  ])('a transient failure (%s) shows a fixed message, keeps no data and recovers on retry', async (_label, failure, message) => {
    const athlete = seedAthlete();
    const t27 = backend.addSeason(2027);
    backend.addSportsRow(athlete.id, t27.id, { categoria: 'Sub 14' });
    backend.override('get', `/admin/deportistas/${athlete.id}/temporadas`, failure, { once: true });
    open(`/admin/deportistas/${athlete.id}/temporadas`);
    const user = userEvent.setup();

    expect(await screen.findByText(message)).toBeInTheDocument();
    expect(screen.queryByRole('list', { name: 'Historial deportivo' })).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Reintentar' }));
    expect(await screen.findByRole('list', { name: 'Historial deportivo' })).toBeInTheDocument();
  });

  it('a failure while loading the athlete header is an error with retry, not a half-drawn screen', async () => {
    const athlete = seedAthlete();
    const t27 = backend.addSeason(2027);
    backend.addSportsRow(athlete.id, t27.id, {});
    backend.override('get', `/admin/deportistas/${athlete.id}`, { status: 500, body: { codigo: 'ERROR_INTERNO' } }, { once: true });
    open(`/admin/deportistas/${athlete.id}/temporadas`);
    const user = userEvent.setup();

    expect(await screen.findByText('Ocurrió un problema en el servidor. Intentá de nuevo en unos minutos.')).toBeInTheDocument();
    expect(screen.queryByRole('list', { name: 'Historial deportivo' })).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Reintentar' }));
    await screen.findByRole('list', { name: 'Historial deportivo' });
  });
});

describe('sports history: an athlete that cannot be shown, and routes that are not valid', () => {
  it('shows one «not found» screen for an athlete that does not exist or belongs to another school', async () => {
    open('/admin/deportistas/dep-inexistente/temporadas');

    expect(await screen.findByRole('heading', { name: 'No encontramos este deportista' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Volver al listado' })).toBeInTheDocument();
  });

  it.each(['x y', '..', 'a.b', 'a_b', 'a'.repeat(65), '%2F', 'dep;1'])('never requests anything for the malformed athlete id %j', async (id) => {
    open(`/admin/deportistas/${encodeURIComponent(id)}/temporadas`);

    expect(await screen.findByRole('heading', { name: 'No encontramos este deportista' })).toBeInTheDocument();
    expect(historyGets()).toHaveLength(0);
    expect(gets(/^\/admin\/deportistas\//)).toHaveLength(0);
    expect(seasonGets()).toHaveLength(0);
  });

  it('sends an anonymous visitor to the admin login and requests nothing', async () => {
    const athlete = seedAthlete();
    renderApp(`/admin/deportistas/${athlete.id}/temporadas`);

    expect(await screen.findByRole('heading', { name: 'Acceso administrativo' })).toBeInTheDocument();
    expect(historyGets()).toHaveLength(0);
  });

  it('keeps a family user out and requests nothing', async () => {
    const athlete = seedAthlete();
    backend.signIn('lucia@example.com');
    renderApp(`/admin/deportistas/${athlete.id}/temporadas`);

    await waitFor(() => expect(screen.queryByRole('heading', { name: 'Datos deportivos' })).not.toBeInTheDocument());
    expect(historyGets()).toHaveLength(0);
  });
});

describe('sports history: the link to load data', () => {
  it('is offered next to the empty state when the school has seasons but the athlete has no data', async () => {
    const athlete = seedAthlete();
    backend.addSeason(2027);
    open(`/admin/deportistas/${athlete.id}/temporadas`);

    await screen.findByRole('heading', { name: 'Este deportista todavía no tiene datos deportivos' });
    expect(screen.getByRole('link', { name: 'Cargar datos de una temporada' })).toHaveAttribute('href', `/admin/deportistas/${athlete.id}/temporadas/nueva`);
  });

  it('is NOT offered when the school has no seasons at all: there is nowhere to load them', async () => {
    const athlete = seedAthlete();
    open(`/admin/deportistas/${athlete.id}/temporadas`);

    await screen.findByRole('heading', { name: 'Todavía no hay temporadas' });
    expect(screen.queryByRole('link', { name: 'Cargar datos de una temporada' })).not.toBeInTheDocument();
  });

  it('is replaced by an explanation for an inactive athlete, because the backend would refuse the load', async () => {
    const athlete = seedAthlete({ activo: false });
    const t27 = backend.addSeason(2027);
    backend.addSportsRow(athlete.id, t27.id, {});
    open(`/admin/deportistas/${athlete.id}/temporadas`);

    await screen.findByRole('list', { name: 'Historial deportivo' });
    expect(screen.queryByRole('link', { name: 'Cargar datos de una temporada' })).not.toBeInTheDocument();
    expect(screen.getByText('Un deportista inactivo no admite datos deportivos nuevos. Reactivalo desde su detalle.')).toBeInTheDocument();
  });

  it('keeps the list filter and page on the way in and out of the load screen', async () => {
    const athlete = seedAthlete();
    backend.addSeason(2027);
    open('/admin/deportistas?estado=TODOS&pagina=1');
    const user = userEvent.setup();

    await user.click(await screen.findByRole('link', { name: 'Sintética, Lucía' }));
    await user.click(await screen.findByRole('link', { name: 'Ver datos deportivos' }));
    await user.click(await screen.findByRole('link', { name: 'Cargar datos de una temporada' }));
    await screen.findByRole('heading', { level: 1, name: 'Cargar datos deportivos' });
    expect(location()).toBe(`/admin/deportistas/${athlete.id}/temporadas/nueva`);
    await user.click(screen.getByRole('link', { name: 'Volver al historial deportivo' }));
    await screen.findByRole('heading', { level: 1, name: 'Datos deportivos' });
    await user.click(screen.getByRole('link', { name: 'Volver al detalle del deportista' }));
    expect(await screen.findByRole('link', { name: 'Volver al listado' })).toHaveAttribute('href', '/admin/deportistas?estado=TODOS&pagina=1');
  });
});
