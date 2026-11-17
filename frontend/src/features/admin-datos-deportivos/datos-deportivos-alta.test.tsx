import { act, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it } from 'vitest';
import type { FakeBackend } from '@/test/fakeBackend';
import { createBackend, renderApp } from '@/test/render';

// MOCK backend (see src/test/fakeBackend.ts, which mirrors POST /api/admin/deportistas/{id}/temporadas in the real backend's order of
// errors). Real-backend evidence lives in e2e/deportistas-datos-deportivos-alta.real.spec.ts.
let backend: FakeBackend;

beforeEach(() => {
  localStorage.clear();
  sessionStorage.clear();
  backend = createBackend();
});

const location = () => screen.getByTestId('location').textContent ?? '';
const posts = () => backend.requests.filter((r) => r.method === 'post' && /^\/admin\/deportistas\/[^/]+\/temporadas$/.test(r.url));
const otherWrites = () => backend.requests.filter((r) => r.method !== 'get' && !r.url.startsWith('/auth/') && !/^\/admin\/deportistas\/[^/]+\/temporadas$/.test(r.url));
const seasonListGets = () => backend.requests.filter((r) => r.method === 'get' && /^\/admin\/temporadas\?/.test(r.url));
const alta = (id: string) => `/admin/deportistas/${id}/temporadas/nueva`;

function seedAthlete(over: { activo?: boolean } = {}) {
  return backend.addAdminAthlete({ nombre: 'Lucía', apellido: 'Sintética', dni: '30111322', cuil: '20301113227', domicilio: 'Calle Falsa 123', ...over });
}

function open(path: string) {
  backend.signIn('marcos@example.com');
  return renderApp(path);
}

const seasonSelect = () => screen.getByLabelText(/^Temporada/);
const optionNames = () => within(seasonSelect()).getAllByRole('option').map((option) => option.textContent);
const field = (label: RegExp) => screen.getByLabelText(label);
const submitButton = () => screen.getByRole('button', { name: /^(Cargar datos|Cargando…)$/ });

async function chooseSeason(user: ReturnType<typeof userEvent.setup>, name: string) {
  await user.selectOptions(seasonSelect(), within(seasonSelect()).getByRole('option', { name }));
}

/** Opens the form for an athlete with the given seasons (none used unless a row is added). */
async function openForm(setup: (athlete: ReturnType<typeof seedAthlete>) => void = () => undefined) {
  const athlete = seedAthlete();
  setup(athlete);
  open(alta(athlete.id));
  await screen.findByRole('form', { name: 'Cargar datos deportivos' });
  return athlete;
}

describe('load sports data: how the administrator gets there and what the selector offers', () => {
  it('is reached from the sports history', async () => {
    const athlete = seedAthlete();
    const t26 = backend.addSeason(2026);
    backend.addSeason(2027);
    backend.addSportsRow(athlete.id, t26.id, {});
    open(`/admin/deportistas/${athlete.id}/temporadas`);
    const user = userEvent.setup();

    await user.click(await screen.findByRole('link', { name: 'Cargar datos de una temporada' }));

    expect(await screen.findByRole('heading', { level: 1, name: 'Cargar datos deportivos' })).toBeInTheDocument();
    expect(location()).toBe(alta(athlete.id));
  });

  it('offers only the seasons in which the athlete has no row, newest first', async () => {
    await openForm((athlete) => {
      const t31 = backend.addSeason(2031);
      backend.addSeason(2032);
      backend.addSeason(2033);
      backend.addSportsRow(athlete.id, t31.id, {});
    });

    expect(optionNames()).toEqual(['Elegí una temporada', 'Temporada 2033', 'Temporada 2032']);
  });

  it('flags the active season in its own option and offers the inactive ones too', async () => {
    await openForm(() => {
      backend.addSeason(2031);
      backend.addSeason(2032, true);
      backend.addSeason(2033);
    });

    expect(optionNames()).toEqual(['Elegí una temporada', 'Temporada 2033', 'Temporada 2032 (activa)', 'Temporada 2031']);
  });

  it('reads EVERY page of the seasons, not just the first', async () => {
    await openForm(() => {
      for (let i = 0; i < 120; i += 1) backend.addSeason(3000 + i);
    });

    expect(optionNames()).toHaveLength(121); // the placeholder and the 120 seasons
    expect(optionNames()[1]).toBe('Temporada 3119');
    expect(optionNames()[120]).toBe('Temporada 3000');
    expect(seasonListGets().map((r) => r.url)).toEqual(['/admin/temporadas?page=0&size=100', '/admin/temporadas?page=1&size=100']);
  });

  it('stops asking for pages when the total is reached', async () => {
    await openForm(() => {
      for (let i = 0; i < 100; i += 1) backend.addSeason(3000 + i);
    });

    expect(optionNames()).toHaveLength(101);
    expect(seasonListGets().map((r) => r.url)).toEqual(['/admin/temporadas?page=0&size=100']);
  });

  it('says there are no seasons when the school has none, and offers no way to create one', async () => {
    const athlete = seedAthlete();
    open(alta(athlete.id));

    expect(await screen.findByRole('heading', { name: 'Todavía no hay temporadas' })).toBeInTheDocument();
    expect(screen.queryByRole('form', { name: 'Cargar datos deportivos' })).not.toBeInTheDocument();
    expect(within(screen.getByRole('main')).queryByRole('link', { name: /crear|nueva|activar/i })).not.toBeInTheDocument();
    expect(posts()).toHaveLength(0);
  });

  it('says every season is already used when the athlete has data in all of them', async () => {
    const athlete = seedAthlete();
    const t26 = backend.addSeason(2026);
    const t27 = backend.addSeason(2027);
    backend.addSportsRow(athlete.id, t26.id, {});
    backend.addSportsRow(athlete.id, t27.id, {});
    open(alta(athlete.id));

    expect(await screen.findByRole('heading', { name: 'Ya hay datos en todas las temporadas' })).toBeInTheDocument();
    expect(screen.queryByRole('form', { name: 'Cargar datos deportivos' })).not.toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Volver al historial deportivo' })).toHaveAttribute('href', `/admin/deportistas/${athlete.id}/temporadas`);
  });

  it('a failed list of seasons is an error with a retry, not an empty selector', async () => {
    const athlete = seedAthlete();
    backend.addSeason(2027);
    backend.override('get', '/admin/temporadas?page=0&size=100', { status: 500, body: { codigo: 'ERROR_INTERNO' } }, { once: true });
    open(alta(athlete.id));
    const user = userEvent.setup();

    expect(await screen.findByText('Ocurrió un problema en el servidor. Intentá de nuevo en unos minutos.')).toBeInTheDocument();
    expect(screen.queryByRole('form', { name: 'Cargar datos deportivos' })).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Reintentar' }));
    expect(await screen.findByRole('form', { name: 'Cargar datos deportivos' })).toBeInTheDocument();
    expect(optionNames()).toEqual(['Elegí una temporada', 'Temporada 2027']);
  });

  it('a failed history is an error with a retry', async () => {
    const athlete = seedAthlete();
    backend.addSeason(2027);
    backend.override('get', `/admin/deportistas/${athlete.id}/temporadas`, 'network');
    open(alta(athlete.id));
    const user = userEvent.setup();

    expect(await screen.findByText('No pudimos conectar con el servidor. Revisá tu conexión e intentá de nuevo.')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Reintentar' }));
    expect(await screen.findByRole('form', { name: 'Cargar datos deportivos' })).toBeInTheDocument();
  });

  it('shows a loading status while the lists are in flight', async () => {
    const athlete = seedAthlete();
    backend.addSeason(2027);
    const held = backend.hold('get', `/admin/deportistas/${athlete.id}/temporadas`);
    open(alta(athlete.id));

    expect(await screen.findByText('Cargando los datos deportivos…')).toHaveAttribute('role', 'status');
    held.release();
    await screen.findByRole('form', { name: 'Cargar datos deportivos' });
  });

  it('an inactive athlete gets no form: the backend would refuse it, and the screen says how to fix it', async () => {
    const athlete = seedAthlete({ activo: false });
    backend.addSeason(2027);
    open(alta(athlete.id));

    expect(await screen.findByRole('heading', { name: 'Este deportista está inactivo' })).toBeInTheDocument();
    expect(screen.queryByRole('form', { name: 'Cargar datos deportivos' })).not.toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Ir al detalle del deportista' })).toHaveAttribute('href', `/admin/deportistas/${athlete.id}`);
    expect(posts()).toHaveLength(0);
  });

  it('an athlete that does not exist is the «not found» screen', async () => {
    open(alta('dep-inexistente'));

    expect(await screen.findByRole('heading', { name: 'No encontramos este deportista' })).toBeInTheDocument();
  });

  it.each(['x y', '..', 'a'.repeat(65)])('never requests anything for the malformed athlete id %j', async (id) => {
    open(alta(encodeURIComponent(id)));

    expect(await screen.findByRole('heading', { name: 'No encontramos este deportista' })).toBeInTheDocument();
    expect(backend.requests.filter((r) => /\/(deportistas|temporadas)/.test(r.url) && !r.url.startsWith('/auth/'))).toHaveLength(0);
  });

  it('sends an anonymous visitor to the admin login and requests nothing', async () => {
    const athlete = seedAthlete();
    renderApp(alta(athlete.id));

    expect(await screen.findByRole('heading', { name: 'Acceso administrativo' })).toBeInTheDocument();
    expect(backend.requests.filter((r) => /\/temporadas/.test(r.url))).toHaveLength(0);
  });

  it('keeps a family user out and requests nothing', async () => {
    const athlete = seedAthlete();
    backend.signIn('lucia@example.com');
    renderApp(alta(athlete.id));

    await waitFor(() => expect(screen.queryByRole('form', { name: 'Cargar datos deportivos' })).not.toBeInTheDocument());
    expect(backend.requests.filter((r) => /\/temporadas/.test(r.url))).toHaveLength(0);
  });
});

describe('load sports data: validation and the exact payload', () => {
  it('asks for the season and sends nothing without one', async () => {
    await openForm(() => backend.addSeason(2027));
    const user = userEvent.setup();

    await user.click(submitButton());

    expect(await screen.findByText('Elegí una temporada.')).toBeInTheDocument();
    expect(posts()).toHaveLength(0);
  });

  it.each([
    [/^Categoría/, 81, 'La categoría puede tener hasta 80 caracteres.'],
    [/^Rama/, 41, 'La rama puede tener hasta 40 caracteres.'],
    [/^Divisional/, 81, 'La divisional puede tener hasta 80 caracteres.'],
    [/^Número de corredor/, 31, 'El número de corredor puede tener hasta 30 caracteres.'],
  ])('shows a too-long %s as an error next to its field and sends nothing', async (label, length, message) => {
    await openForm(() => backend.addSeason(2027));
    const user = userEvent.setup();
    await chooseSeason(user, 'Temporada 2027');
    // maxLength is a hint of the input; the value is set directly so the validation, not the attribute, is what is under test.
    const input = field(label) as HTMLInputElement;
    input.removeAttribute('maxlength');
    await user.click(input);
    await user.paste('x'.repeat(length));

    await user.click(submitButton());

    expect(await screen.findByText(message)).toBeInTheDocument();
    expect(posts()).toHaveLength(0);
  });

  it('limits what can be typed to the length of each column', async () => {
    await openForm(() => backend.addSeason(2027));

    expect(field(/^Categoría/)).toHaveAttribute('maxlength', '80');
    expect(field(/^Rama/)).toHaveAttribute('maxlength', '40');
    expect(field(/^Divisional/)).toHaveAttribute('maxlength', '80');
    expect(field(/^Número de corredor/)).toHaveAttribute('maxlength', '30');
  });

  it('sends EXACTLY what was typed after normalising it: null for blanks, leading zeros kept, the chosen status', async () => {
    await openForm(() => backend.addSeason(2027));
    const user = userEvent.setup();
    await chooseSeason(user, 'Temporada 2027');
    await user.type(field(/^Categoría/), '  Sub   14 ');
    await user.type(field(/^Divisional/), ' A ');
    await user.type(field(/^Número de corredor/), ' 007 ');
    await user.selectOptions(field(/^Estado deportivo/), 'Suspendido');

    await user.click(submitButton());

    await waitFor(() => expect(posts()).toHaveLength(1));
    expect(posts()[0]!.body).toEqual({
      temporadaId: expect.any(String) as string,
      categoria: 'Sub 14',
      rama: null,
      divisional: 'A',
      numeroCorredor: '007',
      estadoDeportivo: 'SUSPENDIDO',
    });
    expect(Object.keys(posts()[0]!.body as object).sort()).toEqual(['categoria', 'divisional', 'estadoDeportivo', 'numeroCorredor', 'rama', 'temporadaId']);
    expect(posts()[0]!.csrfHeader).toBeTruthy();
  });

  it('a season alone is enough: the status defaults to ACTIVO and every optional goes as null', async () => {
    await openForm(() => backend.addSeason(2027));
    const user = userEvent.setup();
    await chooseSeason(user, 'Temporada 2027');
    expect(field(/^Estado deportivo/)).toHaveValue('ACTIVO');

    await user.click(submitButton());

    await waitFor(() => expect(posts()).toHaveLength(1));
    expect(posts()[0]!.body).toMatchObject({ categoria: null, rama: null, divisional: null, numeroCorredor: null, estadoDeportivo: 'ACTIVO' });
  });

  it('keeps the leading zeros of the runner number all the way to the stored row', async () => {
    const athlete = await openForm(() => backend.addSeason(2027));
    const user = userEvent.setup();
    await chooseSeason(user, 'Temporada 2027');
    await user.type(field(/^Número de corredor/), '0008');

    await user.click(submitButton());

    await waitFor(() => expect(backend.sportsRowsOf(athlete.id)).toHaveLength(1));
    expect(backend.sportsRowsOf(athlete.id)[0]!.numeroCorredor).toBe('0008');
    expect(await screen.findByRole('heading', { level: 1, name: 'Temporada 2027' })).toBeInTheDocument();
    expect(within(screen.getByRole('region', { name: 'Datos de la temporada 2027' })).getByText('Número de corredor:').parentElement).toHaveTextContent('0008');
  });
});

describe('load sports data: a confirmed 201', () => {
  it('goes to the detail of the new row with a notice, and the history shows it', async () => {
    const athlete = await openForm(() => backend.addSeason(2027, true));
    const user = userEvent.setup();
    await chooseSeason(user, 'Temporada 2027 (activa)');
    await user.type(field(/^Categoría/), 'Sub 14');

    await user.click(submitButton());

    const heading = await screen.findByRole('heading', { level: 1, name: 'Temporada 2027' });
    expect(heading).toBeInTheDocument();
    const season = backend.sportsRowsOf(athlete.id)[0]!.temporadaId;
    expect(location()).toBe(`/admin/deportistas/${athlete.id}/temporadas/${season}`);
    expect(screen.getByText('Datos deportivos cargados.')).toHaveAttribute('role', 'status');
    expect(screen.getByText('Sub 14')).toBeInTheDocument();
    await user.click(screen.getByRole('link', { name: 'Volver al historial deportivo' }));
    const list = await screen.findByRole('list', { name: 'Historial deportivo' });
    expect(within(list).getByText('Sub 14')).toBeInTheDocument();
  });

  it('shows the notice only when it arrives in the navigation state, never when the detail is opened directly', async () => {
    const athlete = seedAthlete();
    const season = backend.addSeason(2027);
    backend.addSportsRow(athlete.id, season.id, { categoria: 'Sub 14' });
    open(`/admin/deportistas/${athlete.id}/temporadas/${season.id}`);

    await screen.findByRole('heading', { level: 1, name: 'Temporada 2027' });
    expect(screen.queryByText('Datos deportivos cargados.')).not.toBeInTheDocument();
  });

  it('no longer offers the season that was just loaded', async () => {
    const athlete = await openForm(() => {
      backend.addSeason(2027);
      backend.addSeason(2028);
    });
    const user = userEvent.setup();
    await chooseSeason(user, 'Temporada 2027');
    await user.click(submitButton());
    await screen.findByRole('heading', { level: 1, name: 'Temporada 2027' });

    await user.click(screen.getByRole('link', { name: 'Volver al historial deportivo' }));
    await user.click(await screen.findByRole('link', { name: 'Cargar datos de una temporada' }));
    await screen.findByRole('form', { name: 'Cargar datos deportivos' });

    expect(optionNames()).toEqual(['Elegí una temporada', 'Temporada 2028']);
    expect(backend.sportsRowsOf(athlete.id)).toHaveLength(1);
  });
});

describe('load sports data: what the backend can refuse, and the draft that stays', () => {
  async function fillAndSend(user: ReturnType<typeof userEvent.setup>, extra: { numero?: string } = {}) {
    await chooseSeason(user, 'Temporada 2027');
    await user.type(field(/^Categoría/), 'Sub 14');
    if (extra.numero) await user.type(field(/^Número de corredor/), extra.numero);
    await user.click(submitButton());
  }

  it('a runner number already taken in that season is explained next to the number and the draft stays', async () => {
    await openForm(() => {
      const season = backend.addSeason(2027);
      const other = backend.addAdminAthlete({ nombre: 'Eva', apellido: 'Gómez', dni: '30222333' });
      backend.addSportsRow(other.id, season.id, { numeroCorredor: '007' });
    });
    const user = userEvent.setup();

    await fillAndSend(user, { numero: '007' });

    expect(await screen.findByText('Ese número de corredor ya lo tiene otro deportista en esa temporada. Elegí otro o dejalo vacío.')).toBeInTheDocument();
    expect(field(/^Número de corredor/)).toHaveAttribute('aria-invalid', 'true');
    expect(field(/^Categoría/)).toHaveValue('Sub 14');
    expect(seasonSelect()).toHaveValue(within(seasonSelect()).getByRole('option', { name: 'Temporada 2027' }).getAttribute('value'));
    expect(field(/^Número de corredor/)).toBeEnabled();
    expect(location()).toContain('/nueva');

    await user.clear(field(/^Número de corredor/));
    await user.type(field(/^Número de corredor/), '008');
    await user.click(submitButton());
    expect(await screen.findByRole('heading', { level: 1, name: 'Temporada 2027' })).toBeInTheDocument();
  });

  it('a season that got data meanwhile is explained, leaves the selector and keeps the rest of the draft', async () => {
    const athlete = await openForm(() => {
      backend.addSeason(2027);
      backend.addSeason(2028);
    });
    const user = userEvent.setup();
    await chooseSeason(user, 'Temporada 2027');
    await user.type(field(/^Categoría/), 'Sub 14');
    // Another administrator loads the same season after this screen was drawn.
    expect(backend.sportsRowsOf(athlete.id)).toHaveLength(0);
    backend.addSportsRow(athlete.id, within(seasonSelect()).getByRole('option', { name: 'Temporada 2027' }).getAttribute('value')!, { categoria: 'Sub 12' });

    await user.click(submitButton());

    expect(await screen.findByText(/Este deportista ya tiene datos deportivos en esa temporada\./)).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Ver los datos de la temporada 2027' })).toHaveAttribute('href', expect.stringContaining('/temporadas/'));
    await waitFor(() => expect(optionNames()).toEqual(['Elegí una temporada', 'Temporada 2028']));
    expect(seasonSelect()).toHaveValue('');
    expect(field(/^Categoría/)).toHaveValue('Sub 14');
    expect(posts()).toHaveLength(1);
  });

  it('an athlete that became inactive meanwhile gets the inactive screen, not a retry', async () => {
    const athlete = await openForm(() => backend.addSeason(2027));
    const user = userEvent.setup();
    await chooseSeason(user, 'Temporada 2027');
    backend.changeAdminAthleteBehindTheScenes(athlete.id, { activo: false });

    await user.click(submitButton());

    expect(await screen.findByRole('heading', { name: 'Este deportista está inactivo' })).toBeInTheDocument();
    expect(screen.queryByRole('form', { name: 'Cargar datos deportivos' })).not.toBeInTheDocument();
    expect(posts()).toHaveLength(1);
    expect(backend.sportsRowsOf(athlete.id)).toHaveLength(0);
  });

  it('a season that no longer exists is explained and leaves the selector', async () => {
    const athlete = await openForm(() => {
      backend.addSeason(2027);
      backend.addSeason(2028);
    });
    const user = userEvent.setup();
    await chooseSeason(user, 'Temporada 2027');
    backend.override('post', `/admin/deportistas/${athlete.id}/temporadas`, { status: 404, body: { codigo: 'TEMPORADA_NO_ENCONTRADA', mensaje: 'x' } });

    await user.click(submitButton());

    expect(await screen.findByText('Esa temporada ya no existe. Elegí otra.')).toBeInTheDocument();
    expect(posts()).toHaveLength(1);
    expect(screen.getByRole('form', { name: 'Cargar datos deportivos' })).toBeInTheDocument();
  });

  it('an athlete that does not exist any more is the «not found» screen', async () => {
    const athlete = await openForm(() => backend.addSeason(2027));
    const user = userEvent.setup();
    await chooseSeason(user, 'Temporada 2027');
    backend.override('post', `/admin/deportistas/${athlete.id}/temporadas`, { status: 404, body: { codigo: 'DEPORTISTA_NO_ENCONTRADO', mensaje: 'x' } });

    await user.click(submitButton());

    expect(await screen.findByRole('heading', { name: 'No encontramos este deportista' })).toBeInTheDocument();
  });

  it('another kind of conflict means nothing was applied: the same draft can be sent again by the administrator', async () => {
    const athlete = await openForm(() => backend.addSeason(2027));
    const user = userEvent.setup();
    backend.override('post', `/admin/deportistas/${athlete.id}/temporadas`, { status: 409, body: { codigo: 'CONFLICTO_CONCURRENCIA', mensaje: 'x' } });
    await fillAndSend(user);

    expect(await screen.findByText('Otra operación está en curso sobre este deportista. Esperá unos segundos y volvé a intentarlo.')).toBeInTheDocument();
    expect(field(/^Categoría/)).toHaveValue('Sub 14');
    expect(field(/^Categoría/)).toBeEnabled();
    expect(posts()).toHaveLength(1);

    await user.click(submitButton());
    expect(await screen.findByRole('heading', { level: 1, name: 'Temporada 2027' })).toBeInTheDocument();
    expect(posts()).toHaveLength(2);
    expect(posts()[1]!.body).toEqual(posts()[0]!.body);
  });

  it.each([
    ['400 validation', { status: 400, body: { codigo: 'VALIDACION', mensaje: 'texto del servidor' } }, 'Revisá los datos: la temporada es obligatoria y el resto debe respetar los formatos y límites.'],
    ['400 invalid request', { status: 400, body: { codigo: 'SOLICITUD_INVALIDA', mensaje: 'texto del servidor' } }, 'Revisá los datos: la temporada es obligatoria y el resto debe respetar los formatos y límites.'],
    ['403', { status: 403, body: { codigo: 'ACCESO_DENEGADO', mensaje: 'texto del servidor' } }, 'No tenés permiso para cargar datos deportivos.'],
  ])('%s shows a fixed message (never the backend text) and keeps the draft', async (_label, failure, message) => {
    const athlete = await openForm(() => backend.addSeason(2027));
    const user = userEvent.setup();
    backend.override('post', `/admin/deportistas/${athlete.id}/temporadas`, failure);

    await fillAndSend(user);

    expect(await screen.findByText(message)).toBeInTheDocument();
    expect(screen.queryByText('texto del servidor')).not.toBeInTheDocument();
    expect(field(/^Categoría/)).toHaveValue('Sub 14');
    expect(field(/^Categoría/)).toBeEnabled();
  });

  it('warns before leaving with a draft, and leaves only when the administrator decides to', async () => {
    const athlete = await openForm(() => backend.addSeason(2027));
    const user = userEvent.setup();
    await user.type(field(/^Categoría/), 'Sub 14');

    await user.click(screen.getByRole('link', { name: 'Volver al historial deportivo' }));

    expect(await screen.findByRole('button', { name: 'Descartar y salir' })).toBeInTheDocument();
    expect(location()).toBe(alta(athlete.id));
    await user.click(screen.getByRole('button', { name: 'Descartar y salir' }));
    await waitFor(() => expect(location()).toBe(`/admin/deportistas/${athlete.id}/temporadas`));
  });
});

describe('load sports data: while a request is in flight', () => {
  it('locks every field and the button, and the draft cannot change behind the click', async () => {
    const athlete = await openForm(() => backend.addSeason(2027));
    const user = userEvent.setup();
    await chooseSeason(user, 'Temporada 2027');
    await user.type(field(/^Categoría/), 'Sub 14');
    const held = backend.hold('post', `/admin/deportistas/${athlete.id}/temporadas`);

    await user.click(submitButton());

    await waitFor(() => expect(submitButton()).toBeDisabled());
    expect(submitButton()).toHaveAttribute('aria-busy', 'true');
    expect(submitButton()).toHaveTextContent('Cargando…');
    for (const control of [seasonSelect(), field(/^Categoría/), field(/^Rama/), field(/^Divisional/), field(/^Número de corredor/), field(/^Estado deportivo/)]) expect(control).toBeDisabled();
    held.release();
    expect(await screen.findByRole('heading', { level: 1, name: 'Temporada 2027' })).toBeInTheDocument();
    expect(posts()).toHaveLength(1);
  });

  it('three submits in the same task send ONE request', async () => {
    const athlete = await openForm(() => backend.addSeason(2027));
    const user = userEvent.setup();
    await chooseSeason(user, 'Temporada 2027');
    const held = backend.hold('post', `/admin/deportistas/${athlete.id}/temporadas`);
    const form = screen.getByRole('form', { name: 'Cargar datos deportivos' });

    act(() => {
      const button = submitButton();
      button.click();
      button.click();
      form.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
    });
    await waitFor(() => expect(posts()).toHaveLength(1));
    held.release();
    await screen.findByRole('heading', { level: 1, name: 'Temporada 2027' });

    expect(posts()).toHaveLength(1);
    expect(backend.sportsRowsOf(athlete.id)).toHaveLength(1);
    expect(otherWrites()).toEqual([]);
  });
});

describe('load sports data: an answer that cannot be confirmed', () => {
  const FAILURES: [string, 'network' | { status: number; body: unknown }][] = [
    ['a network failure', 'network'],
    ['a 503', { status: 503, body: { codigo: 'NO_DISPONIBLE' } }],
    ['a 500', { status: 500, body: { codigo: 'ERROR_INTERNO' } }],
    ['an unreadable 201', { status: 201, body: { basura: true } }],
  ];

  async function sendUncertain(failure: 'network' | { status: number; body: unknown }) {
    const athlete = await openForm(() => backend.addSeason(2027));
    const user = userEvent.setup();
    backend.override('post', `/admin/deportistas/${athlete.id}/temporadas`, failure);
    await chooseSeason(user, 'Temporada 2027');
    await user.type(field(/^Categoría/), 'Sub 14');
    await user.type(field(/^Número de corredor/), '007');
    await user.click(submitButton());
    await screen.findByText('No pudimos confirmar si los datos se cargaron. Tus datos siguen acá. Antes de volver a intentar, verificá si la temporada ya los tiene.');
    return { athlete, user };
  }

  it.each(FAILURES)('%s locks the form with the draft in view, says it cannot confirm and resends NOTHING', async (_label, failure) => {
    const { athlete } = await sendUncertain(failure);

    expect(field(/^Categoría/)).toHaveValue('Sub 14');
    expect(field(/^Categoría/)).toBeDisabled();
    expect(submitButton()).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Verificar la temporada' })).toBeEnabled();
    await act(async () => { await new Promise((r) => setTimeout(r, 400)); });
    expect(posts()).toHaveLength(1);
    expect(screen.queryByText('Datos deportivos cargados.')).not.toBeInTheDocument();
    expect(location()).toBe(alta(athlete.id));
  });

  it('verifying a row that is NOT there says so, says it proves nothing about a later confirmation, and only then offers a retry', async () => {
    const { athlete, user } = await sendUncertain('network');

    await user.click(screen.getByRole('button', { name: 'Verificar la temporada' }));

    const group = await screen.findByRole('group', { name: 'Verificación' });
    expect(within(group).getByText(/No encontramos datos cargados en esa temporada en este momento\./)).toBeInTheDocument();
    expect(within(group).getByText(/no descarta que el envío anterior se confirme más tarde/)).toBeInTheDocument();
    expect(within(group).queryByText(/se cargaron correctamente|quedó cargado/)).not.toBeInTheDocument();
    expect(posts()).toHaveLength(1);
    expect(backend.requests.filter((r) => r.method === 'get' && new RegExp(`^/admin/deportistas/${athlete.id}/temporadas/[^/]+$`).test(r.url))).toHaveLength(1);

    await user.click(within(group).getByRole('button', { name: 'Volver a intentar' }));
    expect(field(/^Categoría/)).toBeEnabled();
    expect(field(/^Categoría/)).toHaveValue('Sub 14');
    expect(posts()).toHaveLength(1); // opening the form is not sending it
    await user.click(submitButton());
    expect(await screen.findByRole('heading', { level: 1, name: 'Temporada 2027' })).toBeInTheDocument();
    expect(posts()).toHaveLength(2);
  });

  it('a retry after an unconfirmed answer that DID apply is refused as a duplicate, and the screen says it could be the first one', async () => {
    const { athlete, user } = await sendUncertain('network');
    const season = within(seasonSelect()).getByRole('option', { name: 'Temporada 2027' }).getAttribute('value')!;
    backend.addSportsRow(athlete.id, season, { categoria: 'Sub 14', numeroCorredor: '007' }); // what the lost request did on the server
    backend.override('get', `/admin/deportistas/${athlete.id}/temporadas/${season}`, { status: 404, body: { codigo: 'DEPORTISTA_TEMPORADA_NO_ENCONTRADA' } }, { once: true });
    await user.click(screen.getByRole('button', { name: 'Verificar la temporada' }));
    await user.click(await screen.findByRole('button', { name: 'Volver a intentar' }));

    await user.click(submitButton());

    expect(await screen.findByText(/Este deportista ya tiene datos deportivos en esa temporada\./)).toBeInTheDocument();
    expect(screen.getByText('Como el intento anterior no se pudo confirmar, es posible que esos datos sean los que intentaste cargar: no podemos saberlo.')).toBeInTheDocument();
    expect(backend.sportsRowsOf(athlete.id)).toHaveLength(1);
  });

  it('verifying a row that IS there shows its confirmed data and does not claim our request created it', async () => {
    const { athlete, user } = await sendUncertain('network');
    const season = within(seasonSelect()).getByRole('option', { name: 'Temporada 2027' }).getAttribute('value')!;
    backend.addSportsRow(athlete.id, season, { categoria: 'Sub 14', numeroCorredor: '007' });

    await user.click(screen.getByRole('button', { name: 'Verificar la temporada' }));

    const group = await screen.findByRole('group', { name: 'Verificación' });
    expect(within(group).getByText('La temporada ya tiene estos datos cargados:')).toBeInTheDocument();
    expect(within(group).getByText('Sub 14')).toBeInTheDocument();
    expect(within(group).getByText('007')).toBeInTheDocument();
    expect(within(group).getByText('Coinciden con lo que enviaste, pero no podemos saber si los cargó este envío u otro.')).toBeInTheDocument();
    expect(within(group).queryByRole('button', { name: 'Volver a intentar' })).not.toBeInTheDocument();
    expect(within(group).getByRole('link', { name: 'Ver los datos de la temporada 2027' })).toBeInTheDocument();
    expect(screen.queryByText('Datos deportivos cargados.')).not.toBeInTheDocument();
    expect(posts()).toHaveLength(1);
  });

  it('a row that differs from what was sent is shown as different, still without attributing it to the request', async () => {
    const { athlete, user } = await sendUncertain('network');
    const season = within(seasonSelect()).getByRole('option', { name: 'Temporada 2027' }).getAttribute('value')!;
    backend.addSportsRow(athlete.id, season, { categoria: 'Sub 16', numeroCorredor: '099', estadoDeportivo: 'SUSPENDIDO' });

    await user.click(screen.getByRole('button', { name: 'Verificar la temporada' }));

    const group = await screen.findByRole('group', { name: 'Verificación' });
    expect(within(group).getByText('Sub 16')).toBeInTheDocument();
    expect(within(group).getByText('Difieren de lo que enviaste: otra persona u otro envío pudo cargarlos.')).toBeInTheDocument();
    expect(within(group).queryByText(/Coinciden/)).not.toBeInTheDocument();
  });

  it('a verification that fails says so and can be repeated, without resending anything', async () => {
    const { athlete, user } = await sendUncertain('network');
    const season = within(seasonSelect()).getByRole('option', { name: 'Temporada 2027' }).getAttribute('value')!;
    backend.override('get', `/admin/deportistas/${athlete.id}/temporadas/${season}`, 'network', { once: true });

    await user.click(screen.getByRole('button', { name: 'Verificar la temporada' }));

    expect(await screen.findByText('No pudimos verificar la temporada ahora. Intentá de nuevo en unos instantes.')).toBeInTheDocument();
    expect(screen.queryByRole('group', { name: 'Verificación' })).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Verificar la temporada' }));
    expect(await screen.findByRole('group', { name: 'Verificación' })).toBeInTheDocument();
    expect(posts()).toHaveLength(1);
  });

  it('the verification reads the row without keeping it in the cache', async () => {
    const { athlete, user } = await sendUncertain('network');
    const season = within(seasonSelect()).getByRole('option', { name: 'Temporada 2027' }).getAttribute('value')!;
    backend.addSportsRow(athlete.id, season, { categoria: 'Sub 14' });
    await user.click(screen.getByRole('button', { name: 'Verificar la temporada' }));
    await screen.findByRole('group', { name: 'Verificación' });

    expect(backend.requests.filter((r) => r.method === 'get' && new RegExp(`/temporadas/${season}$`).test(r.url))).toHaveLength(1);
  });
});

describe('load sports data: lists that change under an open form', () => {
  it('a season that disappears from the selector is not sent anymore: the placeholder is what is chosen', async () => {
    const athlete = seedAthlete();
    const t27 = backend.addSeason(2027);
    backend.addSeason(2028);
    const { queryClient } = open(alta(athlete.id));
    const user = userEvent.setup();
    await screen.findByRole('form', { name: 'Cargar datos deportivos' });
    await chooseSeason(user, 'Temporada 2027');
    backend.addSportsRow(athlete.id, t27.id, { categoria: 'Cargada por otra persona' }); // another administrator, meanwhile

    await act(async () => { await queryClient.refetchQueries({ type: 'active' }); });
    await waitFor(() => expect(optionNames()).toEqual(['Elegí una temporada', 'Temporada 2028']));
    await user.click(submitButton());

    expect(await screen.findByText('Elegí una temporada.')).toBeInTheDocument();
    expect(posts()).toHaveLength(0);
  });

  it('while an answer is unconfirmed, a list that leaves no season to offer does not hide the way to verify or contradict it', async () => {
    const athlete = seedAthlete();
    const season = backend.addSeason(2027);
    const { queryClient } = open(alta(athlete.id));
    const user = userEvent.setup();
    await screen.findByRole('form', { name: 'Cargar datos deportivos' });
    backend.override('post', `/admin/deportistas/${athlete.id}/temporadas`, 'network');
    await chooseSeason(user, 'Temporada 2027');
    await user.click(submitButton());
    await screen.findByRole('button', { name: 'Verificar la temporada' });
    backend.addSportsRow(athlete.id, season.id, {}); // what the lost request did on the server

    await act(async () => { await queryClient.refetchQueries({ type: 'active' }); });

    expect(screen.queryByRole('heading', { name: 'Ya hay datos en todas las temporadas' })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Verificar la temporada' })).toBeEnabled();
    await user.click(screen.getByRole('button', { name: 'Verificar la temporada' }));
    expect(await screen.findByText('La temporada ya tiene estos datos cargados:')).toBeInTheDocument();
  });

  it('after choosing ANOTHER season, a duplicate there is not blamed on the earlier unconfirmed attempt', async () => {
    const athlete = seedAthlete();
    const t27 = backend.addSeason(2027);
    const t28 = backend.addSeason(2028);
    open(alta(athlete.id));
    const user = userEvent.setup();
    await screen.findByRole('form', { name: 'Cargar datos deportivos' });
    backend.override('post', `/admin/deportistas/${athlete.id}/temporadas`, 'network');
    await chooseSeason(user, 'Temporada 2027');
    await user.click(submitButton());
    await screen.findByRole('button', { name: 'Verificar la temporada' });
    backend.addSportsRow(athlete.id, t27.id, { categoria: 'Sub 14' });
    await user.click(screen.getByRole('button', { name: 'Verificar la temporada' }));
    await user.click(await screen.findByRole('button', { name: 'Elegir otra temporada' }));
    await waitFor(() => expect(optionNames()).toEqual(['Elegí una temporada', 'Temporada 2028']));
    await chooseSeason(user, 'Temporada 2028');
    backend.addSportsRow(athlete.id, t28.id, { categoria: 'Cargada por otra persona' });

    await user.click(submitButton());

    expect(await screen.findByText(/Este deportista ya tiene datos deportivos en esa temporada\./)).toBeInTheDocument();
    expect(screen.queryByText(/Como el intento anterior no se pudo confirmar/)).not.toBeInTheDocument();
  });
});
