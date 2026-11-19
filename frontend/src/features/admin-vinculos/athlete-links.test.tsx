import { act, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it } from 'vitest';
import type { FakeBackend } from '@/test/fakeBackend';
import { createBackend, renderApp } from '@/test/render';
import { CUIL, DNI, REVISION, rawLink, seedAthlete, seedMixedLinks } from './links-fixtures';

// MOCK backend (src/test/fakeBackend.ts mirrors GET /api/admin/deportistas/{id}/vinculos).
// Real-backend evidence lives in e2e/vinculos-consulta.real.spec.ts.
let backend: FakeBackend;

beforeEach(() => {
  localStorage.clear();
  sessionStorage.clear();
  backend = createBackend();
});

const location = () => screen.getByTestId('location').textContent ?? '';
const linkGets = () => backend.requests.filter((r) => r.method === 'get' && /^\/admin\/deportistas\/[^/?]+\/vinculos$/.test(r.url));
const adminRequests = () => backend.requests.filter((r) => r.url.startsWith('/admin/'));
const nonGets = () => backend.requests.filter((r) => r.method !== 'get' && !r.url.startsWith('/auth/'));
const linksPath = (id: string) => `/admin/deportistas/${id}/vinculos`;

function open(path: string) {
  backend.signIn('marcos@example.com');
  return renderApp(path);
}

/** The items of the group with this heading, as the texts of their list items. */
function groupItems(name: string): HTMLElement[] {
  return within(screen.getByRole('list', { name })).getAllByRole('listitem');
}

describe('links of an athlete: what the administrator sees', () => {
  it('names the athlete, tells the state in the school apart and explains who gets access', async () => {
    const { athlete } = seedMixedLinks(backend);
    open(linksPath(athlete.id));

    expect(await screen.findByRole('heading', { level: 1, name: 'Familias vinculadas' })).toBeInTheDocument();
    expect(screen.getByText('Lucía Sintética')).toBeInTheDocument();
    expect(screen.getByText('Estado en la escuela:').parentElement).toHaveTextContent('Activo');
    expect(screen.getByText('Solo un vínculo activo le da acceso a la familia.')).toBeInTheDocument();
    expect(screen.queryByText(/conserva sus vínculos activos/)).not.toBeInTheDocument();
  });

  it('groups active links apart from the previous ones, each family a link to its detail', async () => {
    const { athlete, gomez, perez, ruiz } = seedMixedLinks(backend);
    open(linksPath(athlete.id));
    await screen.findByRole('heading', { level: 1, name: 'Familias vinculadas' });

    expect(screen.getByRole('heading', { level: 2, name: 'Vínculos activos' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { level: 2, name: 'Vínculos anteriores' })).toBeInTheDocument();
    const active = groupItems('Vínculos activos');
    const previous = groupItems('Vínculos anteriores');
    expect(active.map((i) => within(i).getByRole('link').textContent)).toEqual(['Familia Gómez', 'Familia Pérez']);
    expect(previous.map((i) => within(i).getByRole('link').textContent)).toEqual(['Familia Ruiz']);
    expect(within(active[0]!).getByRole('link')).toHaveAttribute('href', `/admin/familias/${gomez.id}`);
    expect(within(active[1]!).getByRole('link')).toHaveAttribute('href', `/admin/familias/${perez.id}`);
    expect(within(previous[0]!).getByRole('link')).toHaveAttribute('href', `/admin/familias/${ruiz.id}`);
    expect(within(active[0]!).getByText('Vínculo activo')).toBeInTheDocument();
    expect(within(previous[0]!).getByText('Vínculo revocado')).toBeInTheDocument();
  });

  it('marks as «Familia principal» ONLY the active principal link, and never speaks of a «tutor principal»', async () => {
    const { athlete } = seedMixedLinks(backend);
    open(linksPath(athlete.id));
    await screen.findByRole('heading', { level: 2, name: 'Vínculos activos' });

    const [first, second] = groupItems('Vínculos activos') as [HTMLElement, HTMLElement];
    expect(within(first).getByText('Familia principal')).toBeInTheDocument();
    expect(within(second).queryByText('Familia principal')).not.toBeInTheDocument();
    expect(within(groupItems('Vínculos anteriores')[0]!).queryByText(/principal/i)).not.toBeInTheDocument();
    expect(screen.getAllByText('Familia principal')).toHaveLength(1);
    expect(document.body).not.toHaveTextContent(/tutor principal/i);
    expect(screen.queryByText('Ninguna de las familias activas está marcada como principal.')).not.toBeInTheDocument();
  });

  it('says so when there are active links and none of them is the principal (revoking never promotes another one)', async () => {
    const athlete = seedAthlete(backend);
    const gomez = backend.addFamily('Familia Gómez');
    const perez = backend.addFamily('Familia Pérez');
    backend.addAdminLink(gomez.id, athlete.id, { estado: 'ACTIVO' });
    backend.addAdminLink(perez.id, athlete.id, { estado: 'ACTIVO' });
    open(linksPath(athlete.id));

    await screen.findByRole('heading', { level: 2, name: 'Vínculos activos' });
    expect(screen.getByText('Ninguna de las familias activas está marcada como principal.')).toBeInTheDocument();
    expect(screen.queryByText('Familia principal')).not.toBeInTheDocument();
  });

  it('a revoked link that still carries the principal flag is NOT shown as principal', async () => {
    const athlete = seedAthlete(backend);
    const gomez = backend.addFamily('Familia Gómez');
    backend.addAdminLink(gomez.id, athlete.id, { estado: 'REVOCADO' });
    backend.override('get', `/admin/deportistas/${athlete.id}/vinculos`, {
      status: 200,
      body: { deportistaId: athlete.id, versionVinculos: '5', vinculos: [rawLink({ deportistaId: athlete.id, familiaId: gomez.id, familiaNombre: 'Familia Gómez', estado: 'REVOCADO', esPrincipal: true, versionVinculos: '5' })] },
    });
    open(linksPath(athlete.id));

    await screen.findByRole('heading', { level: 2, name: 'Vínculos anteriores' });
    expect(screen.queryByText('Familia principal')).not.toBeInTheDocument();
  });

  it.each([
    ['PENDIENTE', 'Vínculo pendiente'],
    ['RECHAZADO', 'Vínculo rechazado'],
    ['REVOCADO', 'Vínculo revocado'],
  ] as const)('a %s link goes with the previous ones and is written as «%s»', async (estado, text) => {
    const athlete = seedAthlete(backend);
    const family = backend.addFamily('Familia Gómez');
    backend.addAdminLink(family.id, athlete.id, { estado });
    open(linksPath(athlete.id));

    await screen.findByRole('heading', { level: 2, name: 'Vínculos anteriores' });
    expect(within(groupItems('Vínculos anteriores')[0]!).getByText(text)).toBeInTheDocument();
    expect(screen.queryByRole('heading', { level: 2, name: 'Vínculos activos' })).not.toBeInTheDocument();
  });

  it('only previous links: says there are no active ones and still lists the history', async () => {
    const athlete = seedAthlete(backend);
    const family = backend.addFamily('Familia Ruiz');
    backend.addAdminLink(family.id, athlete.id, { estado: 'REVOCADO' });
    open(linksPath(athlete.id));

    expect(await screen.findByText('No tiene familias con vínculo activo.')).toBeInTheDocument();
    expect(screen.getByRole('heading', { level: 2, name: 'Vínculos anteriores' })).toBeInTheDocument();
    expect(screen.queryByText('Este deportista todavía no está vinculado con ninguna familia.')).not.toBeInTheDocument();
    expect(screen.queryByText('Ninguna de las familias activas está marcada como principal.')).not.toBeInTheDocument();
  });

  it('zero links: says so, with no group heading and nothing to create', async () => {
    const athlete = seedAthlete(backend);
    open(linksPath(athlete.id));

    expect(await screen.findByText('Este deportista todavía no está vinculado con ninguna familia.')).toBeInTheDocument();
    expect(screen.queryByRole('heading', { level: 2, name: /Vínculos/ })).not.toBeInTheDocument();
    expect(screen.queryByRole('list')).not.toBeInTheDocument();
    expect(screen.queryByText('No tiene familias con vínculo activo.')).not.toBeInTheDocument();
    expect(within(screen.getByRole('main')).queryByRole('button')).not.toBeInTheDocument();
  });

  it('an inactive athlete is marked as inactive and keeps its active links, which are still written as active', async () => {
    const { athlete } = seedMixedLinks(backend, { activo: false });
    open(linksPath(athlete.id));

    await screen.findByRole('heading', { level: 2, name: 'Vínculos activos' });
    expect(screen.getByText('Estado en la escuela:').parentElement).toHaveTextContent('Inactivo');
    expect(screen.getByText('Un deportista inactivo conserva sus vínculos activos: su familia lo sigue viendo, marcado como inactivo.')).toBeInTheDocument();
    expect(within(groupItems('Vínculos activos')[0]!).getByText('Vínculo activo')).toBeInTheDocument();
    expect(within(groupItems('Vínculos activos')[0]!).getByText('Familia principal')).toBeInTheDocument();
  });

  it('keeps the three ideas apart with their own text: state in the school, state of the link, principal', async () => {
    const { athlete } = seedMixedLinks(backend, { activo: false });
    open(linksPath(athlete.id));
    await screen.findByRole('heading', { level: 2, name: 'Vínculos activos' });

    const first = groupItems('Vínculos activos')[0]!;
    expect(screen.getByText('Estado en la escuela:')).toBeInTheDocument();
    expect(within(first).getByText('Vínculo activo')).toBeInTheDocument();
    expect(within(first).getByText('Familia principal')).toBeInTheDocument();
    // The state in the school is not repeated on every item: it is one fact of the athlete, in the header.
    expect(within(first).queryByText('Inactivo')).not.toBeInTheDocument();
  });

  it('never draws the DNI, the CUIL, the address or the revision', async () => {
    const { athlete } = seedMixedLinks(backend);
    open(linksPath(athlete.id));
    await screen.findByRole('heading', { level: 2, name: 'Vínculos activos' });

    const text = document.body.textContent ?? '';
    for (const secret of [DNI, CUIL, 'Calle Falsa', String(REVISION), 'versión', 'revisión']) expect(text.toLowerCase(), secret).not.toContain(secret.toLowerCase());
    expect(location()).toBe(linksPath(athlete.id));
  });
});

describe('links of an athlete: loading, error and retry', () => {
  it('shows a loading state while the answer is on its way', async () => {
    const { athlete } = seedMixedLinks(backend);
    backend.signIn('marcos@example.com');
    const held = backend.hold('get', `/admin/deportistas/${athlete.id}/vinculos`);
    renderApp(linksPath(athlete.id));

    expect(await screen.findByText('Cargando los vínculos…')).toBeInTheDocument();
    expect(screen.queryByRole('heading', { level: 2, name: 'Vínculos activos' })).not.toBeInTheDocument();
    held.release();
    expect(await screen.findByRole('heading', { level: 2, name: 'Vínculos activos' })).toBeInTheDocument();
    expect(screen.queryByText('Cargando los vínculos…')).not.toBeInTheDocument();
  });

  it('a failed load gives a fixed message (never the backend one) and «Reintentar» loads it', async () => {
    const { athlete } = seedMixedLinks(backend);
    backend.override('get', `/admin/deportistas/${athlete.id}/vinculos`, { status: 503, body: { codigo: 'X', mensaje: 'detalle interno del servidor' } });
    open(linksPath(athlete.id));
    const user = userEvent.setup();

    expect(await screen.findByRole('alert')).toHaveTextContent('El servicio no está disponible por el momento. Intentá de nuevo en unos instantes.');
    expect(document.body).not.toHaveTextContent('detalle interno del servidor');
    expect(screen.queryByRole('heading', { level: 2, name: 'Vínculos activos' })).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Reintentar' }));

    expect(await screen.findByRole('heading', { level: 2, name: 'Vínculos activos' })).toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    expect(linkGets()).toHaveLength(2);
  });

  it('a network failure has its own fixed message', async () => {
    const { athlete } = seedMixedLinks(backend);
    backend.override('get', `/admin/deportistas/${athlete.id}/vinculos`, 'network', { once: false });
    open(linksPath(athlete.id));
    expect(await screen.findByRole('alert')).toHaveTextContent('No pudimos conectar con el servidor. Revisá tu conexión e intentá de nuevo.');
  });

  it('a 403 says the administrator has no permission and nothing is drawn', async () => {
    const { athlete } = seedMixedLinks(backend);
    backend.override('get', `/admin/deportistas/${athlete.id}/vinculos`, { status: 403, body: { codigo: 'ACCESO_DENEGADO', mensaje: 'x' } });
    open(linksPath(athlete.id));

    expect(await screen.findByRole('alert')).toHaveTextContent('No tenés permiso para ver los vínculos.');
    expect(screen.queryByText('Familia Gómez')).not.toBeInTheDocument();
  });

  it('a malformed answer is an error with the fixed server message and nothing of it is drawn', async () => {
    const { athlete } = seedMixedLinks(backend);
    backend.override('get', `/admin/deportistas/${athlete.id}/vinculos`, {
      status: 200,
      body: { deportistaId: athlete.id, versionVinculos: '3', vinculos: [rawLink({ deportistaId: athlete.id, versionVinculos: '3', estado: 'OTRO', familiaNombre: 'Familia Filtrada' })] },
    });
    open(linksPath(athlete.id));

    expect(await screen.findByRole('alert')).toHaveTextContent('Ocurrió un problema en el servidor. Intentá de nuevo en unos minutos.');
    expect(document.body).not.toHaveTextContent('Familia Filtrada');
  });

  it('a REFRESH that fails does not take the data off the screen', async () => {
    const { athlete } = seedMixedLinks(backend);
    const { queryClient } = open(linksPath(athlete.id));
    await screen.findByRole('heading', { level: 2, name: 'Vínculos activos' });

    backend.override('get', `/admin/deportistas/${athlete.id}/vinculos`, 'network');
    await act(async () => {
      await queryClient.refetchQueries({ queryKey: ['admin', 'athlete-links', athlete.id] });
    });
    // The observer tells React AFTER the act: wait until the failure really reached the component, so «nothing changed on screen» is not
    // true only because the screen had not rendered yet.
    await waitFor(() => expect(queryClient.getQueryState(['admin', 'athlete-links', athlete.id])?.status).toBe('error'));
    await act(async () => {
      await new Promise((resolve) => setTimeout(resolve, 0));
    });

    expect(linkGets()).toHaveLength(2);
    expect(screen.getByRole('heading', { level: 2, name: 'Vínculos activos' })).toBeInTheDocument();
    expect(screen.getByText('Familia Gómez')).toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('a refresh that works shows what changed on the server (a revoked link moves to the previous ones, nobody is promoted)', async () => {
    const { athlete, gomezLink } = seedMixedLinks(backend);
    const { queryClient } = open(linksPath(athlete.id));
    await screen.findByRole('heading', { level: 2, name: 'Vínculos activos' });

    backend.changeAdminLinkBehindTheScenes(gomezLink.id, { estado: 'REVOCADO' });
    await act(async () => {
      await queryClient.refetchQueries({ queryKey: ['admin', 'athlete-links', athlete.id] });
    });

    await waitFor(() => expect(groupItems('Vínculos activos').map((i) => within(i).getByRole('link').textContent)).toEqual(['Familia Pérez']));
    expect(groupItems('Vínculos anteriores').map((i) => within(i).getByRole('link').textContent)).toEqual(['Familia Gómez', 'Familia Ruiz']);
    expect(screen.queryByText('Familia principal')).not.toBeInTheDocument();
    expect(screen.getByText('Ninguna de las familias activas está marcada como principal.')).toBeInTheDocument();
  });
});

describe('links of an athlete: not found and access', () => {
  it.each([404, 400])('a %s is the uniform «not found» screen, with no message of the backend', async (status) => {
    const athlete = seedAthlete(backend);
    backend.override('get', `/admin/deportistas/${athlete.id}/vinculos`, { status, body: { codigo: 'DEPORTISTA_NO_ENCONTRADO', mensaje: 'secreto del servidor' } });
    open(linksPath(athlete.id));

    expect(await screen.findByRole('heading', { name: 'No encontramos este deportista' })).toBeInTheDocument();
    expect(document.body).not.toHaveTextContent('secreto del servidor');
    expect(screen.queryByRole('button', { name: 'Reintentar' })).not.toBeInTheDocument();
  });

  it('an athlete that does not exist is the same «not found» screen', async () => {
    open(linksPath('dep-inexistente'));
    expect(await screen.findByRole('heading', { name: 'No encontramos este deportista' })).toBeInTheDocument();
  });

  it('an id that could alter the path is «not found» and nothing is requested', async () => {
    backend.signIn('marcos@example.com');
    renderApp('/admin/deportistas/a.b/vinculos');
    expect(await screen.findByRole('heading', { name: 'No encontramos este deportista' })).toBeInTheDocument();
    expect(adminRequests()).toHaveLength(0);
  });

  it('sends anonymous visitors to the admin login and never requests the links', async () => {
    const athlete = seedAthlete(backend);
    renderApp(linksPath(athlete.id));
    expect(await screen.findByRole('heading', { name: 'Acceso administrativo' })).toBeInTheDocument();
    expect(adminRequests()).toHaveLength(0);
  });

  it('keeps a family user out and never requests the links', async () => {
    const { athlete } = seedMixedLinks(backend);
    backend.signIn('lucia@example.com');
    renderApp(linksPath(athlete.id));
    expect(await screen.findByRole('heading', { name: 'No tenés permiso para ver esta sección' })).toBeInTheDocument();
    expect(adminRequests()).toHaveLength(0);
    expect(document.body).not.toHaveTextContent('Familia Gómez');
  });

  it('an administrator whose MFA is pending is sent to the MFA step and nothing is requested', async () => {
    const { athlete } = seedMixedLinks(backend);
    backend.signIn('nuevo@example.com', true);
    renderApp(linksPath(athlete.id));
    await waitFor(() => expect(location()).toBe('/admin/mfa'));
    expect(adminRequests()).toHaveLength(0);
  });
});

describe('links of an athlete: navigation', () => {
  it('opens the detail of a family from its name and goes back to the athlete detail', async () => {
    const { athlete, gomez } = seedMixedLinks(backend);
    open(linksPath(athlete.id));
    const user = userEvent.setup();

    await screen.findByRole('link', { name: 'Familia Gómez' });
    expect(screen.getByRole('link', { name: 'Volver al detalle del deportista' })).toHaveAttribute('href', `/admin/deportistas/${athlete.id}`);
    await user.click(screen.getByRole('link', { name: 'Familia Gómez' }));
    expect(location()).toBe(`/admin/familias/${gomez.id}`);
    expect(await screen.findByRole('heading', { level: 1, name: 'Familia Gómez' })).toBeInTheDocument();
  });

  it('the athlete detail has a clear way into the links, after the sports data, and it only navigates', async () => {
    const { athlete } = seedMixedLinks(backend);
    open(`/admin/deportistas/${athlete.id}`);
    const user = userEvent.setup();

    await screen.findByRole('heading', { level: 1, name: 'Lucía Sintética' });
    expect(linkGets()).toHaveLength(0); // the card is only an access: nothing is asked until the administrator goes in
    const sports = screen.getByRole('region', { name: 'Datos deportivos' });
    const section = screen.getByRole('region', { name: 'Familias vinculadas' });
    expect(sports.compareDocumentPosition(section) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expect(within(section).queryByRole('button')).not.toBeInTheDocument();
    const link = within(section).getByRole('link', { name: 'Ver familias vinculadas' });
    expect(link).toHaveAttribute('href', linksPath(athlete.id));

    await user.click(link);
    expect(location()).toBe(linksPath(athlete.id));
    expect(await screen.findByRole('heading', { level: 2, name: 'Vínculos activos' })).toBeInTheDocument();
    await user.click(screen.getByRole('link', { name: 'Volver al detalle del deportista' }));
    expect(location()).toBe(`/admin/deportistas/${athlete.id}`);
  });

  it('keeps the list the administrator came from (filter and page only) on the way back to the athlete', async () => {
    const { athlete } = seedMixedLinks(backend);
    backend.signIn('marcos@example.com');
    renderApp('/admin/deportistas?estado=TODOS');
    const user = userEvent.setup();

    await user.click(await screen.findByRole('link', { name: /Sintética/ }));
    await screen.findByRole('heading', { level: 1, name: 'Lucía Sintética' });
    await user.click(screen.getByRole('link', { name: 'Ver familias vinculadas' }));
    await screen.findByRole('heading', { level: 2, name: 'Vínculos activos' });
    await user.click(screen.getByRole('link', { name: 'Volver al detalle del deportista' }));
    await screen.findByRole('heading', { level: 1, name: 'Lucía Sintética' });
    expect(location()).toBe(`/admin/deportistas/${athlete.id}`);
    await user.click(screen.getByRole('link', { name: 'Volver al listado' }));
    expect(location()).toBe('/admin/deportistas?estado=TODOS');
  });
});

describe('links of an athlete: consultation without implicit writes', () => {
  it('offers no create or reactivate action and sends only reads before confirmation', async () => {
    const { athlete } = seedMixedLinks(backend);
    open(linksPath(athlete.id));
    await screen.findByRole('heading', { level: 2, name: 'Vínculos activos' });

    const main = within(screen.getByRole('main'));
    expect(main.queryByRole('button', { name: /crear|vincular|reactivar|agregar/i })).not.toBeInTheDocument();
    expect(main.queryAllByRole('checkbox')).toEqual([]);
    expect(main.queryAllByRole('radio')).toEqual([]);
    expect(main.queryAllByRole('textbox')).toEqual([]);
    expect(main.queryAllByRole('combobox')).toEqual([]);
    expect(main.queryByRole('link', { name: /vincular|revocar|reactivar|principal.*(cambiar|marcar)|agregar|crear|editar/i })).not.toBeInTheDocument();
    expect(main.getAllByRole('link').map((l) => l.textContent)).toEqual(['Volver al detalle del deportista', 'Familia Gómez', 'Familia Pérez', 'Familia Ruiz']);
    expect(nonGets()).toEqual([]);
    expect(backend.writes()).toEqual([]);
  });

  it('asks for the athlete header, links and family eligibility, with no personal data in any address', async () => {
    const { athlete } = seedMixedLinks(backend);
    open(linksPath(athlete.id));
    await screen.findByRole('heading', { level: 2, name: 'Vínculos activos' });

    const allowed = [new RegExp(`^/admin/deportistas/${athlete.id}$`), new RegExp(`^/admin/deportistas/${athlete.id}/vinculos$`), /^\/admin\/familias\/[^/]+$/];
    for (const request of adminRequests()) {
      expect(request.method).toBe('get');
      expect(allowed.some((pattern) => pattern.test(request.url)), request.url).toBe(true);
      for (const secret of [DNI, CUIL, 'Lucía']) expect(request.url).not.toContain(secret);
    }
    expect(linkGets()).toHaveLength(1);
  });
});
