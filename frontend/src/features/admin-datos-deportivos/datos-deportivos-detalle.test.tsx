import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it } from 'vitest';
import type { FakeBackend } from '@/test/fakeBackend';
import { createBackend, renderApp } from '@/test/render';

// MOCK backend (see src/test/fakeBackend.ts, which mirrors GET /api/admin/deportistas/{id}/temporadas/{temporadaId}).
// Real-backend evidence lives in e2e/deportistas-datos-deportivos.real.spec.ts.
let backend: FakeBackend;

beforeEach(() => {
  localStorage.clear();
  sessionStorage.clear();
  backend = createBackend();
});

const DNI = '30111322';
const location = () => screen.getByTestId('location').textContent ?? '';
const rowGets = () => backend.requests.filter((r) => r.method === 'get' && /^\/admin\/deportistas\/[^/?]+\/temporadas\/[^/?]+$/.test(r.url));
const sportsRequests = () => backend.requests.filter((r) => /\/temporadas/.test(r.url) && !r.url.startsWith('/auth/'));

function seedAthlete() {
  return backend.addAdminAthlete({ nombre: 'Lucía', apellido: 'Sintética', dni: DNI, cuil: '20301113227' });
}

function open(path: string) {
  backend.signIn('marcos@example.com');
  return renderApp(path);
}

describe('season detail: what the administrator sees', () => {
  it('shows the year, category, branch, division, runner number and the sports status of THAT year', async () => {
    const athlete = seedAthlete();
    const season = backend.addSeason(2027);
    backend.addSportsRow(athlete.id, season.id, { categoria: 'Sub 14', rama: 'Femenino', divisional: 'A', numeroCorredor: '007', estadoDeportivo: 'SUSPENDIDO' });
    open(`/admin/deportistas/${athlete.id}/temporadas/${season.id}`);

    expect(await screen.findByRole('heading', { level: 1, name: 'Temporada 2027' })).toBeInTheDocument();
    const data = screen.getByRole('region', { name: 'Datos de la temporada 2027' });
    const row = (label: string) => within(data).getByText(label).parentElement as HTMLElement;
    expect(row('Categoría:')).toHaveTextContent('Sub 14');
    expect(row('Rama:')).toHaveTextContent('Femenino');
    expect(row('Divisional:')).toHaveTextContent('A');
    expect(row('Número de corredor:')).toHaveTextContent('007');
    expect(row('Estado deportivo de 2027:')).toHaveTextContent('Suspendido');
    expect(rowGets()).toHaveLength(1);
  });

  it('shows «No informado» for every missing optional value', async () => {
    const athlete = seedAthlete();
    const season = backend.addSeason(2026);
    backend.addSportsRow(athlete.id, season.id, {});
    open(`/admin/deportistas/${athlete.id}/temporadas/${season.id}`);

    const data = await screen.findByRole('region', { name: 'Datos de la temporada 2026' });
    expect(within(data).getAllByText('No informado')).toHaveLength(4);
    expect(within(data).getByText('Estado deportivo de 2026:').parentElement).toHaveTextContent('Activo');
  });

  it('validates the revision but never shows it, and never shows the athlete personal data', async () => {
    const athlete = seedAthlete();
    const season = backend.addSeason(2027);
    backend.addSportsRow(athlete.id, season.id, { categoria: 'Sub 14', version: 4242 });
    open(`/admin/deportistas/${athlete.id}/temporadas/${season.id}`);

    await screen.findByRole('heading', { level: 1, name: 'Temporada 2027' });
    const text = document.body.textContent ?? '';
    expect(text).not.toContain('4242');
    expect(text).not.toContain(DNI);
    expect(text).not.toContain('Sintética');
  });

  it('goes back to the sports history of the same athlete', async () => {
    const athlete = seedAthlete();
    const season = backend.addSeason(2027);
    backend.addSportsRow(athlete.id, season.id, {});
    open(`/admin/deportistas/${athlete.id}/temporadas/${season.id}`);
    const user = userEvent.setup();

    await screen.findByRole('heading', { level: 1, name: 'Temporada 2027' }); // the loading screen has its own «Volver» link: wait for the data first
    await user.click(screen.getByRole('link', { name: 'Volver al historial deportivo' }));
    await screen.findByRole('heading', { level: 1, name: 'Datos deportivos' });
    expect(location()).toBe(`/admin/deportistas/${athlete.id}/temporadas`);
  });

  it('offers the way to EDIT the row (U10-C) but no way to load, activate or create anything, and sends only reads', async () => {
    const athlete = seedAthlete();
    const season = backend.addSeason(2027);
    backend.addSportsRow(athlete.id, season.id, {});
    open(`/admin/deportistas/${athlete.id}/temporadas/${season.id}`);

    await screen.findByRole('heading', { level: 1, name: 'Temporada 2027' });
    const main = within(screen.getByRole('main'));
    expect(main.queryByRole('button')).not.toBeInTheDocument();
    expect(main.getByRole('link', { name: 'Editar datos deportivos' })).toHaveAttribute('href', `/admin/deportistas/${athlete.id}/temporadas/${season.id}/editar`);
    expect(main.queryByRole('link', { name: /cargar|activar|crear|eliminar|borrar/i })).not.toBeInTheDocument();
    expect(backend.requests.filter((r) => r.method !== 'get' && !r.url.startsWith('/auth/'))).toEqual([]);
  });
});

describe('season detail: loading and errors', () => {
  it('shows a loading status while the row is in flight', async () => {
    const athlete = seedAthlete();
    const season = backend.addSeason(2027);
    backend.addSportsRow(athlete.id, season.id, {});
    const held = backend.hold('get', `/admin/deportistas/${athlete.id}/temporadas/${season.id}`);
    open(`/admin/deportistas/${athlete.id}/temporadas/${season.id}`);

    expect(await screen.findByText('Cargando los datos deportivos…')).toHaveAttribute('role', 'status');
    held.release();
    await screen.findByRole('heading', { level: 1, name: 'Temporada 2027' });
  });

  it.each([
    ['network', 'network' as const, 'No pudimos conectar con el servidor. Revisá tu conexión e intentá de nuevo.'],
    ['503', { status: 503, body: { codigo: 'NO_DISPONIBLE' } }, 'El servicio no está disponible por el momento. Intentá de nuevo en unos instantes.'],
    ['500', { status: 500, body: { codigo: 'ERROR_INTERNO' } }, 'Ocurrió un problema en el servidor. Intentá de nuevo en unos minutos.'],
    ['an unreadable 200', { status: 200, body: { basura: true } }, 'Ocurrió un problema en el servidor. Intentá de nuevo en unos minutos.'],
    ['403', { status: 403, body: { codigo: 'ACCESO_DENEGADO' } }, 'No tenés permiso para ver los datos deportivos.'],
  ])('a transient failure (%s) shows a fixed message and recovers on retry', async (_label, failure, message) => {
    const athlete = seedAthlete();
    const season = backend.addSeason(2027);
    backend.addSportsRow(athlete.id, season.id, { categoria: 'Sub 14' });
    backend.override('get', `/admin/deportistas/${athlete.id}/temporadas/${season.id}`, failure, { once: true });
    open(`/admin/deportistas/${athlete.id}/temporadas/${season.id}`);
    const user = userEvent.setup();

    expect(await screen.findByText(message)).toBeInTheDocument();
    expect(screen.queryByText('Sub 14')).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Reintentar' }));
    expect(await screen.findByRole('heading', { level: 1, name: 'Temporada 2027' })).toBeInTheDocument();
  });

  it('rejects a revision that is not a decimal string instead of showing the row', async () => {
    const athlete = seedAthlete();
    const season = backend.addSeason(2027);
    backend.override('get', `/admin/deportistas/${athlete.id}/temporadas/${season.id}`, {
      status: 200,
      body: { id: 'r', deportistaId: athlete.id, temporadaId: season.id, anio: 2027, categoria: 'Sub 14', rama: null, divisional: null, numeroCorredor: null, estadoDeportivo: 'ACTIVO', version: 3 },
    });
    open(`/admin/deportistas/${athlete.id}/temporadas/${season.id}`);

    expect(await screen.findByText('Ocurrió un problema en el servidor. Intentá de nuevo en unos minutos.')).toBeInTheDocument();
    expect(screen.queryByText('Sub 14')).not.toBeInTheDocument();
  });
});

describe('season detail: each kind of «not found» is told apart', () => {
  it('an athlete that does not exist (or belongs to another school) is the athlete «not found» screen', async () => {
    const season = backend.addSeason(2027);
    open(`/admin/deportistas/dep-inexistente/temporadas/${season.id}`);

    expect(await screen.findByRole('heading', { name: 'No encontramos este deportista' })).toBeInTheDocument();
  });

  it('a season that does not exist says so and links back to the history', async () => {
    const athlete = seedAthlete();
    open(`/admin/deportistas/${athlete.id}/temporadas/temp-inexistente`);

    expect(await screen.findByRole('heading', { name: 'No encontramos esta temporada' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Volver al historial deportivo' })).toHaveAttribute('href', `/admin/deportistas/${athlete.id}/temporadas`);
  });

  it('a season that exists but where the athlete has no sports row says exactly that', async () => {
    const athlete = seedAthlete();
    const season = backend.addSeason(2027);
    open(`/admin/deportistas/${athlete.id}/temporadas/${season.id}`);

    expect(await screen.findByRole('heading', { name: 'Sin datos deportivos en esta temporada' })).toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: 'No encontramos esta temporada' })).not.toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Volver al historial deportivo' })).toBeInTheDocument();
  });

  it('a 404 with a code it does not know is the neutral «season not found» screen, never a raw message', async () => {
    const athlete = seedAthlete();
    const season = backend.addSeason(2027);
    backend.override('get', `/admin/deportistas/${athlete.id}/temporadas/${season.id}`, { status: 404, body: { codigo: 'OTRA_COSA', mensaje: 'texto del servidor' } });
    open(`/admin/deportistas/${athlete.id}/temporadas/${season.id}`);

    expect(await screen.findByRole('heading', { name: 'No encontramos esta temporada' })).toBeInTheDocument();
    expect(screen.queryByText('texto del servidor')).not.toBeInTheDocument();
  });

  it('another athlete\'s season row is never shown under this athlete', async () => {
    const mine = seedAthlete();
    const other = backend.addAdminAthlete({ nombre: 'Ana', apellido: 'Otra', dni: '30999888' });
    const season = backend.addSeason(2027);
    backend.addSportsRow(other.id, season.id, { categoria: 'Sub 16' });
    open(`/admin/deportistas/${mine.id}/temporadas/${season.id}`);

    expect(await screen.findByRole('heading', { name: 'Sin datos deportivos en esta temporada' })).toBeInTheDocument();
    expect(screen.queryByText('Sub 16')).not.toBeInTheDocument();
  });
});

describe('season detail: routes that are not valid', () => {
  it.each(['x y', '..', 'a.b', 'a'.repeat(65), 'dep;1'])('never requests anything for the malformed season id %j', async (id) => {
    const athlete = seedAthlete();
    open(`/admin/deportistas/${athlete.id}/temporadas/${encodeURIComponent(id)}`);

    expect(await screen.findByRole('heading', { name: 'No encontramos esta temporada' })).toBeInTheDocument();
    expect(sportsRequests()).toHaveLength(0);
    expect(screen.getByRole('link', { name: 'Volver al historial deportivo' })).toHaveAttribute('href', `/admin/deportistas/${athlete.id}/temporadas`);
  });

  it.each(['x y', '..', 'a'.repeat(65)])('never requests anything for the malformed athlete id %j', async (id) => {
    const season = backend.addSeason(2027);
    open(`/admin/deportistas/${encodeURIComponent(id)}/temporadas/${season.id}`);

    expect(await screen.findByRole('heading', { name: 'No encontramos este deportista' })).toBeInTheDocument();
    expect(sportsRequests()).toHaveLength(0);
  });

  it('sends an anonymous visitor to the admin login and requests nothing', async () => {
    const athlete = seedAthlete();
    const season = backend.addSeason(2027);
    backend.addSportsRow(athlete.id, season.id, {});
    renderApp(`/admin/deportistas/${athlete.id}/temporadas/${season.id}`);

    expect(await screen.findByRole('heading', { name: 'Acceso administrativo' })).toBeInTheDocument();
    expect(rowGets()).toHaveLength(0);
  });

  it('keeps a family user out and requests nothing', async () => {
    const athlete = seedAthlete();
    const season = backend.addSeason(2027);
    backend.addSportsRow(athlete.id, season.id, {});
    backend.signIn('lucia@example.com');
    renderApp(`/admin/deportistas/${athlete.id}/temporadas/${season.id}`);

    await waitFor(() => expect(screen.queryByRole('heading', { level: 1, name: 'Temporada 2027' })).not.toBeInTheDocument());
    expect(rowGets()).toHaveLength(0);
  });
});
