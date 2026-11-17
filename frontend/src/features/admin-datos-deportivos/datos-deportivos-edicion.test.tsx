import { act, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it } from 'vitest';
import type { FakeBackend, FakeSeason, FakeSportsRow } from '@/test/fakeBackend';
import { createBackend, renderApp } from '@/test/render';

// MOCK backend (see src/test/fakeBackend.ts, which mirrors PUT /api/admin/deportistas/{id}/temporadas/{temporadaId} in the real
// backend's order: 404, the REVISION of the row, uniqueness, write). Real-backend evidence lives in
// e2e/deportistas-datos-deportivos-edicion.real.spec.ts.
let backend: FakeBackend;

beforeEach(() => {
  localStorage.clear();
  sessionStorage.clear();
  backend = createBackend();
});

const location = () => screen.getByTestId('location').textContent ?? '';
const edit = (athleteId: string, seasonId: string) => `/admin/deportistas/${athleteId}/temporadas/${seasonId}/editar`;
const rowPath = (athleteId: string, seasonId: string) => `/admin/deportistas/${athleteId}/temporadas/${seasonId}`;
const puts = () => backend.requests.filter((r) => r.method === 'put' && /^\/admin\/deportistas\/[^/]+\/temporadas\/[^/]+$/.test(r.url));
const rowGets = (athleteId: string, seasonId: string) => backend.requests.filter((r) => r.method === 'get' && r.url === `/admin/deportistas/${athleteId}/temporadas/${seasonId}`);
const otherWrites = () => backend.requests.filter((r) => r.method !== 'get' && !r.url.startsWith('/auth/') && !/^\/admin\/deportistas\/[^/]+\/temporadas\/[^/]+$/.test(r.url));
const field = (label: RegExp) => screen.getByLabelText(label);
const saveButton = () => screen.getByRole('button', { name: /^(Guardar cambios|Guardando…)$/ });

const STALE = 'Otra persona pudo haber modificado estos datos mientras los editabas. Tu borrador sigue acá, pero todavía no se guardó y el guardado queda bloqueado hasta que revises los datos vigentes.';
const UNCERTAIN = 'No pudimos confirmar si el cambio se guardó. Tu borrador sigue acá. Verificá los datos vigentes antes de volver a intentar.';

interface Seeded {
  athlete: ReturnType<FakeBackend['addAdminAthlete']>;
  season: FakeSeason;
  row: FakeSportsRow;
}

/** An athlete with ONE row (revision 3) in season 2027. The athlete is active; the season is not (an inactive season is still editable). */
function seed(over: { athleteActive?: boolean; row?: Partial<Omit<FakeSportsRow, 'id' | 'deportistaId' | 'temporadaId'>> } = {}): Seeded {
  const athlete = backend.addAdminAthlete({ nombre: 'Lucía', apellido: 'Sintética', dni: '30111322', cuil: '20301113227', domicilio: 'Calle Falsa 123', activo: over.athleteActive ?? true });
  const season = backend.addSeason(2027);
  const row = backend.addSportsRow(athlete.id, season.id, { categoria: 'Sub 14', rama: 'Femenino', divisional: 'A', numeroCorredor: '0007', estadoDeportivo: 'SUSPENDIDO', version: 3, ...over.row });
  return { athlete, season, row };
}

function open(path: string) {
  backend.signIn('marcos@example.com');
  return renderApp(path);
}

async function openForm(over: Parameters<typeof seed>[0] = {}) {
  const seeded = seed(over);
  const rendered = open(edit(seeded.athlete.id, seeded.season.id));
  await screen.findByRole('form', { name: 'Editar datos deportivos' });
  return { ...seeded, ...rendered };
}

async function replace(user: ReturnType<typeof userEvent.setup>, label: RegExp, value: string) {
  const input = field(label);
  await user.clear(input);
  if (value !== '') await user.type(input, value);
}

describe('edit sports data: how it opens', () => {
  it('is reached from the season detail through its own link', async () => {
    const { athlete, season } = seed();
    open(rowPath(athlete.id, season.id));
    const user = userEvent.setup();

    await user.click(await screen.findByRole('link', { name: 'Editar datos deportivos' }));

    expect(await screen.findByRole('heading', { level: 1, name: 'Editar datos deportivos' })).toBeInTheDocument();
    expect(location()).toBe(edit(athlete.id, season.id));
  });

  it('opens with the stored values of the five fields and the revision of THAT read, and the season and athlete are not fields', async () => {
    const { athlete, season } = await openForm();

    expect(field(/^Categoría/)).toHaveValue('Sub 14');
    expect(field(/^Rama/)).toHaveValue('Femenino');
    expect(field(/^Divisional/)).toHaveValue('A');
    expect(field(/^Número de corredor/)).toHaveValue('0007');
    expect(field(/^Estado deportivo de 2027/)).toHaveValue('SUSPENDIDO');
    expect(screen.queryByLabelText(/^Temporada/)).not.toBeInTheDocument();
    expect(screen.queryByLabelText(/^(Nombre|Apellido|DNI)/)).not.toBeInTheDocument();
    expect(rowGets(athlete.id, season.id)).toHaveLength(1);
  });

  it('tells the sports status of the year apart from the state of the athlete in the school', async () => {
    await openForm({ athleteActive: false });

    expect(screen.getByText('Estado en la escuela:').parentElement).toHaveTextContent('Inactivo');
    expect(field(/^Estado deportivo de 2027/)).toHaveValue('SUSPENDIDO');
    expect(screen.getByText('Lucía Sintética')).toBeInTheDocument();
  });

  it('edits a row of an INACTIVE season of an INACTIVE athlete: the backend allows it', async () => {
    const { athlete, season } = await openForm({ athleteActive: false });
    const user = userEvent.setup();
    await replace(user, /^Categoría/, 'Sub 16');

    await user.click(saveButton());

    await waitFor(() => expect(backend.sportsRowsOf(athlete.id)[0]!.categoria).toBe('Sub 16'));
    expect(season.activa).toBe(false);
    expect(await screen.findByRole('heading', { level: 1, name: 'Temporada 2027' })).toBeInTheDocument();
  });

  it('shows a loading status while the row is in flight and does not draw the form before it is read', async () => {
    const { athlete, season } = seed();
    const held = backend.hold('get', `/admin/deportistas/${athlete.id}/temporadas/${season.id}`);
    open(edit(athlete.id, season.id));

    expect(await screen.findByText('Cargando los datos deportivos…')).toHaveAttribute('role', 'status');
    expect(screen.queryByRole('form', { name: 'Editar datos deportivos' })).not.toBeInTheDocument();
    held.release();
    await screen.findByRole('form', { name: 'Editar datos deportivos' });
  });

  it.each([
    ['network', 'network' as const, 'No pudimos conectar con el servidor. Revisá tu conexión e intentá de nuevo.'],
    ['503', { status: 503, body: { codigo: 'NO_DISPONIBLE' } }, 'El servicio no está disponible por el momento. Intentá de nuevo en unos instantes.'],
    ['an unreadable 200', { status: 200, body: { basura: true } }, 'Ocurrió un problema en el servidor. Intentá de nuevo en unos minutos.'],
    ['403', { status: 403, body: { codigo: 'ACCESO_DENEGADO' } }, 'No tenés permiso para ver los datos deportivos.'],
  ])('a failed read (%s) is an error with a retry, never a form', async (_label, failure, message) => {
    const { athlete, season } = seed();
    backend.override('get', `/admin/deportistas/${athlete.id}/temporadas/${season.id}`, failure, { once: true });
    open(edit(athlete.id, season.id));
    const user = userEvent.setup();

    expect(await screen.findByText(message)).toBeInTheDocument();
    expect(screen.queryByRole('form', { name: 'Editar datos deportivos' })).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Reintentar' }));
    expect(await screen.findByRole('form', { name: 'Editar datos deportivos' })).toBeInTheDocument();
  });

  it('an athlete that does not exist is the athlete «not found» screen', async () => {
    const { season } = seed();
    open(edit('dep-inexistente', season.id));

    expect(await screen.findByRole('heading', { name: 'No encontramos este deportista' })).toBeInTheDocument();
  });

  it('a season that does not exist is the season «not found» screen', async () => {
    const { athlete } = seed();
    open(edit(athlete.id, 'temp-inexistente'));

    expect(await screen.findByRole('heading', { name: 'No encontramos esta temporada' })).toBeInTheDocument();
  });

  it('a season where the athlete has no row says exactly that', async () => {
    const { athlete } = seed();
    const other = backend.addSeason(2028);
    open(edit(athlete.id, other.id));

    expect(await screen.findByRole('heading', { name: 'Sin datos deportivos en esta temporada' })).toBeInTheDocument();
  });

  it.each(['x y', '..', 'a'.repeat(65)])('never requests anything for the malformed id %j', async (id) => {
    const { athlete, season } = seed();
    open(edit(encodeURIComponent(id), season.id));
    expect(await screen.findByRole('heading', { name: 'No encontramos este deportista' })).toBeInTheDocument();
    expect(backend.requests.filter((r) => /\/(deportistas|temporadas)/.test(r.url) && !r.url.startsWith('/auth/'))).toHaveLength(0);
    expect(athlete.id).toBeDefined();
  });

  it('sends an anonymous visitor to the admin login and requests nothing', async () => {
    const { athlete, season } = seed();
    renderApp(edit(athlete.id, season.id));

    expect(await screen.findByRole('heading', { name: 'Acceso administrativo' })).toBeInTheDocument();
    expect(backend.requests.filter((r) => /\/temporadas/.test(r.url))).toHaveLength(0);
  });

  it('keeps a family user out and requests nothing', async () => {
    const { athlete, season } = seed();
    backend.signIn('lucia@example.com');
    renderApp(edit(athlete.id, season.id));

    await waitFor(() => expect(screen.queryByRole('form', { name: 'Editar datos deportivos' })).not.toBeInTheDocument());
    expect(backend.requests.filter((r) => /\/temporadas/.test(r.url))).toHaveLength(0);
  });
});

describe('edit sports data: saving a complete replacement', () => {
  it('sends the five fields and the revision of the read as a decimal STRING, with an explicit null for what was emptied', async () => {
    const { athlete, season } = await openForm();
    const user = userEvent.setup();
    await replace(user, /^Categoría/, '');
    await replace(user, /^Rama/, '   ');

    await user.click(saveButton());

    await waitFor(() => expect(puts()).toHaveLength(1));
    expect(puts()[0]!.url).toBe(`/admin/deportistas/${athlete.id}/temporadas/${season.id}`);
    expect(puts()[0]!.body).toEqual({ categoria: null, rama: null, divisional: 'A', numeroCorredor: '0007', estadoDeportivo: 'SUSPENDIDO', versionEsperada: '3' });
    expect(typeof (puts()[0]!.body as { versionEsperada: unknown }).versionEsperada).toBe('string');
    expect(puts()[0]!.csrfHeader).toBeTruthy();
    expect(Object.keys(puts()[0]!.body as object)).not.toContain('temporadaId');
  });

  it('sends an explicit null for EVERY optional that was emptied (division and number too), never an empty string', async () => {
    const { athlete } = await openForm();
    const user = userEvent.setup();
    await replace(user, /^Categoría/, '');
    await replace(user, /^Rama/, '');
    await replace(user, /^Divisional/, '  ');
    await replace(user, /^Número de corredor/, '');

    await user.click(saveButton());

    await waitFor(() => expect(puts()).toHaveLength(1));
    expect(puts()[0]!.body).toEqual({ categoria: null, rama: null, divisional: null, numeroCorredor: null, estadoDeportivo: 'SUSPENDIDO', versionEsperada: '3' });
    await screen.findByRole('heading', { level: 1, name: 'Temporada 2027' });
    expect(backend.sportsRowsOf(athlete.id)[0]).toMatchObject({ categoria: null, rama: null, divisional: null, numeroCorredor: null });
  });

  it('normalises what was typed (spaces) and keeps the leading zeros of a number that was not touched', async () => {
    const { athlete } = await openForm();
    const user = userEvent.setup();
    await replace(user, /^Categoría/, '  Sub   16 ');

    await user.click(saveButton());

    await waitFor(() => expect(puts()).toHaveLength(1));
    expect(puts()[0]!.body).toMatchObject({ categoria: 'Sub 16', numeroCorredor: '0007' });
    expect(backend.sportsRowsOf(athlete.id)[0]!.numeroCorredor).toBe('0007');
  });

  it('a change of the sports status alone is a change', async () => {
    await openForm();
    const user = userEvent.setup();

    await user.selectOptions(field(/^Estado deportivo de 2027/), 'Activo');
    await user.click(saveButton());

    await waitFor(() => expect(puts()).toHaveLength(1));
    expect(puts()[0]!.body).toMatchObject({ estadoDeportivo: 'ACTIVO' });
  });

  it('names, without values, the saved data an edit would remove', async () => {
    await openForm();
    const user = userEvent.setup();
    expect(screen.queryByText(/Se van a quitar estos datos guardados/)).not.toBeInTheDocument();

    await replace(user, /^Categoría/, '');
    await replace(user, /^Número de corredor/, '');

    const note = screen.getByText(/Se van a quitar estos datos guardados/);
    expect(note).toHaveTextContent('Categoría, Número de corredor');
    expect(note).not.toHaveTextContent('Sub 14');
    expect(note).not.toHaveTextContent('0007');
  });

  it('goes to the detail with a notice once the backend confirmed, and the row really changed (the revision moved by one)', async () => {
    const { athlete, season } = await openForm();
    const user = userEvent.setup();
    await replace(user, /^Categoría/, 'Sub 16');

    await user.click(saveButton());

    expect(await screen.findByRole('heading', { level: 1, name: 'Temporada 2027' })).toBeInTheDocument();
    expect(location()).toBe(rowPath(athlete.id, season.id));
    expect(screen.getByText('Cambios guardados.')).toHaveAttribute('role', 'status');
    expect(screen.getByText('Sub 16')).toBeInTheDocument();
    expect(backend.sportsRowVersion(athlete.id, season.id)).toBe('4');
  });

  it('the detail shows the «saved» notice only when it arrives in the navigation state, never when it is opened directly', async () => {
    const { athlete, season } = seed();
    open(rowPath(athlete.id, season.id));

    await screen.findByRole('heading', { level: 1, name: 'Temporada 2027' });
    expect(screen.queryByText('Cambios guardados.')).not.toBeInTheDocument();
    expect(screen.queryByText('Datos deportivos cargados.')).not.toBeInTheDocument();
  });

  it('has nothing to save when the draft equals what was read: the button is off and no PUT can be sent', async () => {
    await openForm();
    const user = userEvent.setup();
    expect(saveButton()).toBeDisabled();

    await replace(user, /^Categoría/, ' Sub  14 '); // only what normalisation removes
    expect(saveButton()).toBeDisabled();
    act(() => {
      screen.getByRole('form', { name: 'Editar datos deportivos' }).dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
    });

    await act(async () => { await new Promise((r) => setTimeout(r, 300)); });
    expect(puts()).toHaveLength(0);
  });

  it.each([
    [/^Categoría/, 81, 'La categoría puede tener hasta 80 caracteres.'],
    [/^Número de corredor/, 31, 'El número de corredor puede tener hasta 30 caracteres.'],
  ])('shows a too-long %s as an error next to its field and sends nothing', async (label, length, message) => {
    await openForm();
    const user = userEvent.setup();
    const input = field(label) as HTMLInputElement;
    input.removeAttribute('maxlength');
    await user.clear(input);
    await user.click(input);
    await user.paste('x'.repeat(length));

    await user.click(saveButton());

    expect(await screen.findByText(message)).toBeInTheDocument();
    expect(puts()).toHaveLength(0);
  });

  it('warns before leaving with unsaved changes, and leaves only when the administrator decides to', async () => {
    const { athlete, season } = await openForm();
    const user = userEvent.setup();
    await replace(user, /^Categoría/, 'Sub 16');

    await user.click(screen.getByRole('link', { name: 'Cancelar' }));

    expect(await screen.findByRole('button', { name: 'Descartar y salir' })).toBeInTheDocument();
    expect(location()).toBe(edit(athlete.id, season.id));
    await user.click(screen.getByRole('button', { name: 'Descartar y salir' }));
    await waitFor(() => expect(location()).toBe(rowPath(athlete.id, season.id)));
    expect(puts()).toHaveLength(0);
  });

  it('does not warn when nothing changed', async () => {
    const { athlete, season } = await openForm();
    const user = userEvent.setup();

    await user.click(screen.getByRole('link', { name: 'Cancelar' }));

    await waitFor(() => expect(location()).toBe(rowPath(athlete.id, season.id)));
    expect(screen.queryByRole('button', { name: 'Descartar y salir' })).not.toBeInTheDocument();
  });
});

describe('edit sports data: while the request is in flight', () => {
  it('locks every field and the button, and the draft cannot change behind the click', async () => {
    const { athlete, season } = await openForm();
    const user = userEvent.setup();
    await replace(user, /^Categoría/, 'Sub 16');
    const held = backend.hold('put', `/admin/deportistas/${athlete.id}/temporadas/${season.id}`);

    await user.click(saveButton());

    await waitFor(() => expect(saveButton()).toBeDisabled());
    expect(saveButton()).toHaveAttribute('aria-busy', 'true');
    expect(saveButton()).toHaveTextContent('Guardando…');
    for (const control of [field(/^Categoría/), field(/^Rama/), field(/^Divisional/), field(/^Número de corredor/), field(/^Estado deportivo de 2027/)]) expect(control).toBeDisabled();
    held.release();
    expect(await screen.findByRole('heading', { level: 1, name: 'Temporada 2027' })).toBeInTheDocument();
    expect(puts()).toHaveLength(1);
  });

  it('three submits in the same task send ONE request and move the revision once', async () => {
    const { athlete, season } = await openForm();
    const user = userEvent.setup();
    await replace(user, /^Categoría/, 'Sub 16');
    const held = backend.hold('put', `/admin/deportistas/${athlete.id}/temporadas/${season.id}`);
    const form = screen.getByRole('form', { name: 'Editar datos deportivos' });

    act(() => {
      const button = saveButton();
      button.click();
      button.click();
      form.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
    });
    await waitFor(() => expect(puts()).toHaveLength(1));
    held.release();
    await screen.findByRole('heading', { level: 1, name: 'Temporada 2027' });

    expect(puts()).toHaveLength(1);
    expect(backend.sportsRowVersion(athlete.id, season.id)).toBe('4');
    expect(otherWrites()).toEqual([]);
  });
});

describe('edit sports data: what the backend can refuse, and the draft that stays', () => {
  it('a runner number taken by another athlete in that season is explained next to the number; the draft and the revision stay', async () => {
    const { athlete, season } = await openForm();
    const other = backend.addAdminAthlete({ nombre: 'Eva', apellido: 'Gómez', dni: '30222333' });
    backend.addSportsRow(other.id, season.id, { numeroCorredor: '0099' });
    const user = userEvent.setup();
    await replace(user, /^Número de corredor/, '0099');

    await user.click(saveButton());

    expect(await screen.findByText('Ese número de corredor ya lo tiene otro deportista en esa temporada. Elegí otro o dejalo vacío.')).toBeInTheDocument();
    expect(field(/^Número de corredor/)).toHaveAttribute('aria-invalid', 'true');
    expect(field(/^Número de corredor/)).toHaveValue('0099');
    expect(field(/^Número de corredor/)).toBeEnabled();
    expect(backend.sportsRowVersion(athlete.id, season.id)).toBe('3');
    expect(location()).toContain('/editar');

    await replace(user, /^Número de corredor/, '0100');
    await user.click(saveButton());
    expect(await screen.findByRole('heading', { level: 1, name: 'Temporada 2027' })).toBeInTheDocument();
    expect(puts()).toHaveLength(2);
    expect(puts()[1]!.body).toMatchObject({ versionEsperada: '3', numeroCorredor: '0100' }); // nothing was applied by the first: the same revision
  });

  it('keeping its OWN runner number is not a duplicate', async () => {
    const { athlete } = await openForm();
    const user = userEvent.setup();
    await replace(user, /^Categoría/, 'Sub 16');

    await user.click(saveButton());

    await screen.findByRole('heading', { level: 1, name: 'Temporada 2027' });
    expect(backend.sportsRowsOf(athlete.id)[0]!.numeroCorredor).toBe('0007');
  });

  it('another kind of conflict means nothing was applied: a message, the same draft and revision, and an explicit retry', async () => {
    const { athlete, season } = await openForm();
    const user = userEvent.setup();
    backend.override('put', `/admin/deportistas/${athlete.id}/temporadas/${season.id}`, { status: 409, body: { codigo: 'CONFLICTO_CONCURRENCIA', mensaje: 'texto del servidor' } });
    await replace(user, /^Categoría/, 'Sub 16');

    await user.click(saveButton());

    expect(await screen.findByText('Otra operación está en curso sobre estos datos. Esperá unos segundos y volvé a intentarlo. No se guardó nada.')).toBeInTheDocument();
    expect(screen.queryByText('texto del servidor')).not.toBeInTheDocument();
    expect(field(/^Categoría/)).toHaveValue('Sub 16');
    expect(field(/^Categoría/)).toBeEnabled();
    expect(puts()).toHaveLength(1);

    await user.click(saveButton());
    expect(await screen.findByRole('heading', { level: 1, name: 'Temporada 2027' })).toBeInTheDocument();
    expect(puts()).toHaveLength(2);
    expect(puts()[1]!.body).toEqual(puts()[0]!.body);
  });

  it.each([
    ['400 validation', { status: 400, body: { codigo: 'VALIDACION', mensaje: 'texto del servidor' } }, 'Revisá los datos: el estado deportivo es obligatorio y el resto debe respetar los formatos y límites.'],
    ['400 invalid request', { status: 400, body: { codigo: 'SOLICITUD_INVALIDA', mensaje: 'texto del servidor' } }, 'Revisá los datos: el estado deportivo es obligatorio y el resto debe respetar los formatos y límites.'],
    ['403', { status: 403, body: { codigo: 'ACCESO_DENEGADO', mensaje: 'texto del servidor' } }, 'No tenés permiso para editar datos deportivos.'],
  ])('%s shows a fixed message (never the backend text) and keeps the draft', async (_label, failure, message) => {
    const { athlete, season } = await openForm();
    const user = userEvent.setup();
    backend.override('put', `/admin/deportistas/${athlete.id}/temporadas/${season.id}`, failure);
    await replace(user, /^Categoría/, 'Sub 16');

    await user.click(saveButton());

    expect(await screen.findByText(message)).toBeInTheDocument();
    expect(screen.queryByText('texto del servidor')).not.toBeInTheDocument();
    expect(field(/^Categoría/)).toHaveValue('Sub 16');
    expect(field(/^Categoría/)).toBeEnabled();
  });

  it.each([
    ['DEPORTISTA_NO_ENCONTRADO', 'No encontramos este deportista'],
    ['TEMPORADA_NO_ENCONTRADA', 'No encontramos esta temporada'],
    ['DEPORTISTA_TEMPORADA_NO_ENCONTRADA', 'Sin datos deportivos en esta temporada'],
  ])('a 404 %s replaces the form with its own screen', async (code, title) => {
    const { athlete, season } = await openForm();
    const user = userEvent.setup();
    backend.override('put', `/admin/deportistas/${athlete.id}/temporadas/${season.id}`, { status: 404, body: { codigo: code, mensaje: 'x' } });
    await replace(user, /^Categoría/, 'Sub 16');

    await user.click(saveButton());

    expect(await screen.findAllByRole('heading', { name: title })).not.toHaveLength(0);
    expect(screen.queryByRole('form', { name: 'Editar datos deportivos' })).not.toBeInTheDocument();
  });
});

describe('edit sports data: a stale revision', () => {
  /** Opens the form, makes the draft, lets ANOTHER administrator write first and sends: the backend answers 409 EDICION_DESACTUALIZADA. */
  async function conflictOn(others: Parameters<FakeBackend['changeSportsRowBehindTheScenes']>[2], mine: { categoria?: string; rama?: string; numero?: string } = { categoria: 'Sub 16' }) {
    const opened = await openForm();
    const user = userEvent.setup();
    if (mine.categoria !== undefined) await replace(user, /^Categoría/, mine.categoria);
    if (mine.rama !== undefined) await replace(user, /^Rama/, mine.rama);
    if (mine.numero !== undefined) await replace(user, /^Número de corredor/, mine.numero);
    backend.changeSportsRowBehindTheScenes(opened.athlete.id, opened.season.id, others);
    await user.click(saveButton());
    await screen.findByText(STALE);
    return { ...opened, user };
  }

  it('keeps the draft, locks the form, blocks the save and resends NOTHING by itself', async () => {
    const { athlete, season } = await conflictOn({ divisional: 'B' });

    expect(field(/^Categoría/)).toHaveValue('Sub 16');
    expect(field(/^Categoría/)).toBeDisabled();
    expect(saveButton()).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Ver datos vigentes' })).toBeEnabled();
    await act(async () => { await new Promise((r) => setTimeout(r, 400)); });
    expect(puts()).toHaveLength(1);
    expect(rowGets(athlete.id, season.id)).toHaveLength(1); // no automatic read either
    expect(backend.sportsRowVersion(athlete.id, season.id)).toBe('4'); // the other administrator's write is untouched
    expect(location()).toContain('/editar');
  });

  it('is refused EVEN when the other administrator wrote exactly what this one is sending', async () => {
    const { user } = await conflictOn({ categoria: 'Sub 16' });

    await user.click(screen.getByRole('button', { name: 'Ver datos vigentes' }));

    expect(await screen.findByText(/coinciden con tu borrador/)).toBeInTheDocument();
    expect(puts()).toHaveLength(1);
  });

  it('reads the row again ONLY when asked, and shows the field by field comparison with who changed what', async () => {
    const { athlete, season, user } = await conflictOn({ categoria: 'Sub 18', divisional: 'B', estadoDeportivo: 'ACTIVO' }, { categoria: 'Sub 16', rama: 'Mixto' });

    await user.click(screen.getByRole('button', { name: 'Ver datos vigentes' }));

    const group = await screen.findByRole('group', { name: 'Datos vigentes' });
    expect(rowGets(athlete.id, season.id)).toHaveLength(2);
    const table = within(group).getByRole('table', { name: 'Comparación de datos' });
    const rowOf = (label: string) => within(table).getByRole('rowheader', { name: new RegExp(`^${label}`) }).closest('tr') as HTMLElement;
    expect(rowOf('Categoría')).toHaveTextContent('Conflicto: los dos lo cambiaron distinto');
    expect(rowOf('Rama')).toHaveTextContent('Solo cambiaste vos: se conserva tu valor');
    expect(rowOf('Divisional')).toHaveTextContent('Cambió otra persona: se incorpora el valor vigente');
    expect(rowOf('Estado deportivo')).toHaveTextContent('Cambió otra persona: se incorpora el valor vigente');
    expect(within(table).queryByRole('rowheader', { name: /^Número de corredor/ })).not.toBeInTheDocument(); // nobody changed it
  });

  it('asks for an explicit choice in every real conflict, with NO default, and does not let go on until all are made', async () => {
    const { user } = await conflictOn({ categoria: 'Sub 18', rama: 'Masculino' }, { categoria: 'Sub 16', rama: 'Mixto' });
    await user.click(screen.getByRole('button', { name: 'Ver datos vigentes' }));
    const group = await screen.findByRole('group', { name: 'Datos vigentes' });

    for (const radio of within(group).getAllByRole('radio')) expect(radio).not.toBeChecked();
    expect(within(group).getAllByRole('radio')).toHaveLength(4);
    const goOn = within(group).getByRole('button', { name: 'Seguir editando con los datos vigentes' });
    expect(goOn).toBeDisabled();
    expect(within(group).getByText('Resolvé los 2 conflictos para poder seguir.')).toBeInTheDocument();

    await user.click(within(within(group).getByRole('group', { name: 'Categoría' })).getByRole('radio', { name: /Mantener mi valor: Sub 16/ }));
    expect(goOn).toBeDisabled();
    expect(within(group).getByText('Resolvé el conflicto para poder seguir.')).toBeInTheDocument();
    await user.click(within(within(group).getByRole('group', { name: 'Rama' })).getByRole('radio', { name: /Usar el valor vigente: Masculino/ }));
    expect(goOn).toBeEnabled();
  });

  it('after the decisions the draft keeps what is only the administrator\'s, takes what is only the other one\'s and follows the choices; only THEN the revision is adopted', async () => {
    const { athlete, season, user } = await conflictOn({ categoria: 'Sub 18', divisional: 'B', estadoDeportivo: 'ACTIVO' }, { categoria: 'Sub 16', rama: 'Mixto' });
    await user.click(screen.getByRole('button', { name: 'Ver datos vigentes' }));
    const group = await screen.findByRole('group', { name: 'Datos vigentes' });
    expect(puts()).toHaveLength(1);

    await user.click(within(within(group).getByRole('group', { name: 'Categoría' })).getByRole('radio', { name: /Mantener mi valor/ }));
    await user.click(within(group).getByRole('button', { name: 'Seguir editando con los datos vigentes' }));

    expect(await screen.findByRole('button', { name: 'Guardar cambios' })).toBeEnabled();
    expect(field(/^Categoría/)).toHaveValue('Sub 16'); // the explicit choice
    expect(field(/^Rama/)).toHaveValue('Mixto'); // only mine
    expect(field(/^Divisional/)).toHaveValue('B'); // only theirs: incorporated
    expect(field(/^Estado deportivo de 2027/)).toHaveValue('ACTIVO'); // only theirs: incorporated
    expect(field(/^Categoría/)).toBeEnabled();
    expect(puts()).toHaveLength(1); // nothing was sent by deciding

    await user.click(saveButton());
    await screen.findByRole('heading', { level: 1, name: 'Temporada 2027' });
    expect(puts()).toHaveLength(2);
    expect(puts()[1]!.body).toEqual({ categoria: 'Sub 16', rama: 'Mixto', divisional: 'B', numeroCorredor: '0007', estadoDeportivo: 'ACTIVO', versionEsperada: '4' });
    expect(backend.sportsRowVersion(athlete.id, season.id)).toBe('5');
  });

  it('choosing «the current value» takes it and drops the draft of that field', async () => {
    const { user } = await conflictOn({ categoria: 'Sub 18' }, { categoria: 'Sub 16', rama: 'Mixto' });
    await user.click(screen.getByRole('button', { name: 'Ver datos vigentes' }));
    const group = await screen.findByRole('group', { name: 'Datos vigentes' });

    await user.click(within(within(group).getByRole('group', { name: 'Categoría' })).getByRole('radio', { name: /Usar el valor vigente: Sub 18/ }));
    await user.click(within(group).getByRole('button', { name: 'Seguir editando con los datos vigentes' }));

    expect(await screen.findByRole('button', { name: 'Guardar cambios' })).toBeEnabled();
    expect(field(/^Categoría/)).toHaveValue('Sub 18');
    expect(field(/^Rama/)).toHaveValue('Mixto');
  });

  it('a second change by someone else AFTER the resolution is a new conflict: the revision is never refreshed silently', async () => {
    const { athlete, season, user } = await conflictOn({ divisional: 'B' });
    await user.click(screen.getByRole('button', { name: 'Ver datos vigentes' }));
    await user.click(await screen.findByRole('button', { name: 'Seguir editando con los datos vigentes' }));
    await screen.findByRole('button', { name: 'Guardar cambios' });
    backend.changeSportsRowBehindTheScenes(athlete.id, season.id, { rama: 'Masculino' }); // version 5 on the server; the form holds 4

    await user.click(saveButton());

    expect(await screen.findByText(STALE)).toBeInTheDocument();
    expect(puts()).toHaveLength(2);
    expect(puts()[1]!.body).toMatchObject({ versionEsperada: '4' });
    expect(backend.sportsRowVersion(athlete.id, season.id)).toBe('5');
  });

  it('can be left: «Descartar mi borrador» goes to the detail without writing anything', async () => {
    const { athlete, season, user } = await conflictOn({ categoria: 'Sub 18' }, { categoria: 'Sub 16' });
    await user.click(screen.getByRole('button', { name: 'Ver datos vigentes' }));

    await user.click(await screen.findByRole('button', { name: 'Descartar mi borrador' }));

    await waitFor(() => expect(location()).toBe(rowPath(athlete.id, season.id)));
    expect(puts()).toHaveLength(1);
    expect(backend.sportsRowsOf(athlete.id)[0]!.categoria).toBe('Sub 18');
  });

  it('offers no way to force the save: there is no overwrite and no «use the latest revision» action', async () => {
    const { user } = await conflictOn({ categoria: 'Sub 18' }, { categoria: 'Sub 16' });
    await user.click(screen.getByRole('button', { name: 'Ver datos vigentes' }));
    await screen.findByRole('group', { name: 'Datos vigentes' });

    expect(screen.queryByRole('button', { name: /sobrescribir|forzar|guardar igual|última revisión|reemplazar/i })).not.toBeInTheDocument();
  });

  it('a failed read of the current data says so, keeps the form locked and can be repeated', async () => {
    const { athlete, season, user } = await conflictOn({ categoria: 'Sub 18' }, { categoria: 'Sub 16' });
    backend.override('get', `/admin/deportistas/${athlete.id}/temporadas/${season.id}`, 'network', { once: true });

    await user.click(screen.getByRole('button', { name: 'Ver datos vigentes' }));

    expect(await screen.findByText('No pudimos conectar con el servidor. Revisá tu conexión e intentá de nuevo.')).toBeInTheDocument();
    expect(field(/^Categoría/)).toBeDisabled();
    await user.click(screen.getByRole('button', { name: 'Ver datos vigentes' }));
    expect(await screen.findByRole('group', { name: 'Datos vigentes' })).toBeInTheDocument();
  });

  it('a row that no longer exists when the current data are read is its own screen', async () => {
    const { athlete, season, user } = await conflictOn({ categoria: 'Sub 18' }, { categoria: 'Sub 16' });
    backend.override('get', `/admin/deportistas/${athlete.id}/temporadas/${season.id}`, { status: 404, body: { codigo: 'DEPORTISTA_TEMPORADA_NO_ENCONTRADA', mensaje: 'x' } }, { once: true });

    await user.click(screen.getByRole('button', { name: 'Ver datos vigentes' }));

    expect(await screen.findAllByRole('heading', { name: 'Sin datos deportivos en esta temporada' })).not.toHaveLength(0);
  });

  it('a background refresh of the open form never changes the revision it will send', async () => {
    const { athlete, season, queryClient } = await openForm();
    const user = userEvent.setup();
    await replace(user, /^Categoría/, 'Sub 16');
    backend.changeSportsRowBehindTheScenes(athlete.id, season.id, { divisional: 'B' });

    await act(async () => { await queryClient.refetchQueries({ type: 'active' }); });
    await user.click(saveButton());

    expect(await screen.findByText(STALE)).toBeInTheDocument();
    expect(puts()[0]!.body).toMatchObject({ versionEsperada: '3' }); // the revision of the read at opening, not the refreshed one
    expect(field(/^Categoría/)).toHaveValue('Sub 16');
  });
});

describe('edit sports data: an answer that cannot be confirmed', () => {
  const FAILURES: [string, 'network' | { status: number; body: unknown }][] = [
    ['a network failure', 'network'],
    ['a 503', { status: 503, body: { codigo: 'NO_DISPONIBLE' } }],
    ['a 500', { status: 500, body: { codigo: 'ERROR_INTERNO' } }],
    ['an unreadable 200', { status: 200, body: { basura: true } }],
  ];

  async function sendUncertain(failure: 'network' | { status: number; body: unknown }) {
    const opened = await openForm();
    const user = userEvent.setup();
    backend.override('put', `/admin/deportistas/${opened.athlete.id}/temporadas/${opened.season.id}`, failure);
    await replace(user, /^Categoría/, 'Sub 16');
    await replace(user, /^Rama/, '');
    await user.click(saveButton());
    await screen.findByText(UNCERTAIN);
    return { ...opened, user };
  }

  it.each(FAILURES)('%s locks the form with the draft in view, claims no save and resends NOTHING', async (_label, failure) => {
    const { athlete } = await sendUncertain(failure);

    expect(field(/^Categoría/)).toHaveValue('Sub 16');
    expect(field(/^Categoría/)).toBeDisabled();
    expect(saveButton()).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Verificar datos vigentes' })).toBeEnabled();
    await act(async () => { await new Promise((r) => setTimeout(r, 400)); });
    expect(puts()).toHaveLength(1);
    expect(screen.queryByText('Cambios guardados.')).not.toBeInTheDocument();
    expect(location()).toContain('/editar');
    expect(athlete.id).toBeDefined();
  });

  it('a verification that finds the SAME revision says it sees no saved change, does not rule out that it was applied, and only then offers a retry with that same revision', async () => {
    const { athlete, season, user } = await sendUncertain('network');

    await user.click(screen.getByRole('button', { name: 'Verificar datos vigentes' }));

    const group = await screen.findByRole('group', { name: 'Verificación' });
    expect(within(group).getByText(/La revisión no cambió: no vemos cambios guardados\./)).toBeInTheDocument();
    expect(within(group).getByText(/no descarta del todo que el envío se haya aplicado/)).toBeInTheDocument();
    expect(puts()).toHaveLength(1);

    await user.click(within(group).getByRole('button', { name: 'Volver a intentar' }));
    expect(field(/^Categoría/)).toHaveValue('Sub 16');
    expect(field(/^Categoría/)).toBeEnabled();
    expect(puts()).toHaveLength(1); // opening the form is not sending it
    await user.click(saveButton());
    expect(await screen.findByRole('heading', { level: 1, name: 'Temporada 2027' })).toBeInTheDocument();
    expect(puts()).toHaveLength(2);
    expect(puts()[1]!.body).toMatchObject({ versionEsperada: '3' });
    expect(backend.sportsRowVersion(athlete.id, season.id)).toBe('4');
  });

  it('a revision that moved to values equal to the draft is shown as the state observed, WITHOUT attributing it to the request', async () => {
    const { athlete, season, user } = await sendUncertain('network');
    backend.changeSportsRowBehindTheScenes(athlete.id, season.id, { categoria: 'Sub 16', rama: null }); // what the lost request did, or what somebody else did

    await user.click(screen.getByRole('button', { name: 'Verificar datos vigentes' }));

    const group = await screen.findByRole('group', { name: 'Verificación' });
    expect(within(group).getByText('Los datos vigentes coinciden con tu borrador, pero no podemos saber si los guardó tu envío u otro cambio. No hace falta volver a guardar.')).toBeInTheDocument();
    expect(within(group).queryByRole('button', { name: 'Volver a intentar' })).not.toBeInTheDocument();
    expect(screen.queryByText('Cambios guardados.')).not.toBeInTheDocument();
    expect(puts()).toHaveLength(1);
  });

  it('a revision that moved to DIFFERENT values is compared field by field, and the revision is adopted only after the choices', async () => {
    const { athlete, season, user } = await sendUncertain('network');
    backend.changeSportsRowBehindTheScenes(athlete.id, season.id, { categoria: 'Sub 18', divisional: 'B' });

    await user.click(screen.getByRole('button', { name: 'Verificar datos vigentes' }));

    const group = await screen.findByRole('group', { name: 'Verificación' });
    expect(within(group).getByRole('table', { name: 'Comparación de datos' })).toBeInTheDocument();
    for (const radio of within(group).getAllByRole('radio')) expect(radio).not.toBeChecked();
    expect(within(group).getByRole('button', { name: 'Seguir editando con los datos vigentes' })).toBeDisabled();
    await user.click(within(within(group).getByRole('group', { name: 'Categoría' })).getByRole('radio', { name: /Mantener mi valor/ }));
    await user.click(within(group).getByRole('button', { name: 'Seguir editando con los datos vigentes' }));

    await user.click(await screen.findByRole('button', { name: 'Guardar cambios' }));
    await screen.findByRole('heading', { level: 1, name: 'Temporada 2027' });
    expect(puts()[1]!.body).toMatchObject({ categoria: 'Sub 16', divisional: 'B', versionEsperada: '4' });
  });

  it('a failed verification says so and can be repeated, without resending anything', async () => {
    const { athlete, season, user } = await sendUncertain('network');
    backend.override('get', `/admin/deportistas/${athlete.id}/temporadas/${season.id}`, { status: 503, body: { codigo: 'NO_DISPONIBLE' } }, { once: true });

    await user.click(screen.getByRole('button', { name: 'Verificar datos vigentes' }));

    expect(await screen.findByText('El servicio no está disponible por el momento. Intentá de nuevo en unos instantes.')).toBeInTheDocument();
    expect(screen.queryByRole('group', { name: 'Verificación' })).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Verificar datos vigentes' }));
    expect(await screen.findByRole('group', { name: 'Verificación' })).toBeInTheDocument();
    expect(puts()).toHaveLength(1);
  });

  it('every verification is a FRESH read of the server and never silently updates what the form holds', async () => {
    const { athlete, season, queryClient, user } = await sendUncertain('network');
    const readsBefore = rowGets(athlete.id, season.id).length;
    const cached = (queryClient.getQueryData(['admin', 'athlete-sports', athlete.id, 'row', season.id]) as { version: string }).version;
    backend.changeSportsRowBehindTheScenes(athlete.id, season.id, { divisional: 'B' });

    await user.click(screen.getByRole('button', { name: 'Verificar datos vigentes' }));
    await screen.findByRole('group', { name: 'Verificación' });

    expect(rowGets(athlete.id, season.id).length).toBe(readsBefore + 1);
    expect((queryClient.getQueryData(['admin', 'athlete-sports', athlete.id, 'row', season.id]) as { version: string }).version).toBe(cached);
  });
});

describe('edit sports data: details found by the independent verification', () => {
  const UNCERTAIN_ALERT = 'No pudimos confirmar si el cambio se guardó.';

  async function uncertainAfter(over: Parameters<typeof openForm>[0] = {}) {
    const opened = await openForm(over);
    const user = userEvent.setup();
    backend.override('put', `/admin/deportistas/${opened.athlete.id}/temporadas/${opened.season.id}`, 'network');
    await replace(user, /^Categoría/, 'Sub 16');
    await user.click(saveButton());
    await screen.findByText(new RegExp(UNCERTAIN_ALERT));
    return { ...opened, user };
  }

  it('«Volver a intentar» clears the unconfirmed-result alert: the form is open again and nothing claims to be pending', async () => {
    const { user } = await uncertainAfter();
    await user.click(screen.getByRole('button', { name: 'Verificar datos vigentes' }));
    await user.click(await screen.findByRole('button', { name: 'Volver a intentar' }));

    expect(screen.queryByText(new RegExp(UNCERTAIN_ALERT))).not.toBeInTheDocument();
    expect(field(/^Categoría/)).toBeEnabled();
  });

  it('sends the runner number exactly as stored: inner spaces are data (only the texts collapse them)', async () => {
    await openForm({ row: { numeroCorredor: '12  34' } });
    const user = userEvent.setup();
    await replace(user, /^Categoría/, 'Sub 16');

    await user.click(saveButton());

    await waitFor(() => expect(puts()).toHaveLength(1));
    expect(puts()[0]!.body).toMatchObject({ categoria: 'Sub 16', numeroCorredor: '12  34' });
  });

  it('a revision that moved while no datum ended up different (a change and its undo) says exactly that, in the conflict and in the verification', async () => {
    const { athlete, season } = await openForm();
    const user = userEvent.setup();
    await replace(user, /^Categoría/, 'Sub 16');
    backend.changeSportsRowBehindTheScenes(athlete.id, season.id, { divisional: 'B' });
    backend.changeSportsRowBehindTheScenes(athlete.id, season.id, { divisional: 'A' }); // back to what it was: the revision is 5, the data the same
    await user.click(saveButton());
    await screen.findByText(STALE);

    await user.click(screen.getByRole('button', { name: 'Ver datos vigentes' }));

    const group = await screen.findByRole('group', { name: 'Datos vigentes' });
    expect(within(group).getByText('Los datos vigentes son los mismos que viste al abrir: la revisión cambió pero ningún dato.')).toBeInTheDocument();
    const table = within(group).getByRole('table', { name: 'Comparación de datos' });
    expect(within(table).getByRole('rowheader', { name: /^Categoría/ })).toHaveTextContent('Solo cambiaste vos: se conserva tu valor');
    expect(within(table).queryByRole('rowheader', { name: /^Divisional/ })).not.toBeInTheDocument(); // nothing of the other writer is to be incorporated
    await user.click(within(group).getByRole('button', { name: 'Seguir editando con los datos vigentes' }));
    await user.click(await screen.findByRole('button', { name: 'Guardar cambios' }));
    await screen.findByRole('heading', { level: 1, name: 'Temporada 2027' });
    expect(puts()[1]!.body).toMatchObject({ categoria: 'Sub 16', divisional: 'A', versionEsperada: '5' });
  });

  it('when VERIFYING, the table never says somebody else changed anything: it cannot know who did', async () => {
    const { athlete, season, user } = await uncertainAfter();
    backend.changeSportsRowBehindTheScenes(athlete.id, season.id, { categoria: 'Sub 18', divisional: 'B', rama: 'Mixto' });
    await user.click(screen.getByRole('button', { name: 'Verificar datos vigentes' }));

    const group = await screen.findByRole('group', { name: 'Verificación' });
    const table = within(group).getByRole('table', { name: 'Comparación de datos' });
    expect(table).not.toHaveTextContent(/otra persona|los dos lo cambiaron|solo cambiaste vos/i);
    expect(within(table).getByRole('rowheader', { name: /^Divisional/ })).toHaveTextContent('Cambió el valor vigente desde que abriste: se incorpora');
    expect(within(table).getByRole('rowheader', { name: /^Categoría/ })).toHaveTextContent('El valor vigente y tu borrador son distintos: elegí uno');
  });

  it('a pending PUT does not make leaving silent: the unsaved-changes warning still appears', async () => {
    const { athlete, season } = await openForm();
    const user = userEvent.setup();
    await replace(user, /^Categoría/, 'Sub 16');
    const held = backend.hold('put', `/admin/deportistas/${athlete.id}/temporadas/${season.id}`);
    await user.click(saveButton());
    await waitFor(() => expect(saveButton()).toBeDisabled());

    await user.click(screen.getByRole('link', { name: 'Cancelar' }));

    expect(await screen.findByRole('button', { name: 'Descartar y salir' })).toBeInTheDocument();
    expect(location()).toBe(edit(athlete.id, season.id));
    held.release();
  });

  it('two reads asked in the same task send ONE request (the button disables itself only after React renders)', async () => {
    const { athlete, season } = await uncertainAfter();
    const readsBefore = rowGets(athlete.id, season.id).length;
    const held = backend.hold('get', `/admin/deportistas/${athlete.id}/temporadas/${season.id}`);

    act(() => {
      const button = screen.getByRole('button', { name: 'Verificar datos vigentes' });
      button.click();
      button.click();
    });
    await waitFor(() => expect(rowGets(athlete.id, season.id).length).toBe(readsBefore + 1));
    held.release();
    await screen.findByRole('group', { name: 'Verificación' });

    expect(rowGets(athlete.id, season.id).length).toBe(readsBefore + 1);
  });
});
