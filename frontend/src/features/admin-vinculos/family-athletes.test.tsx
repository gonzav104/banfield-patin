import { act, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it } from 'vitest';
import type { FakeBackend } from '@/test/fakeBackend';
import { createBackend, renderApp } from '@/test/render';
import { CUIL, DNI, REVISION, rawLink, seedAthlete } from './links-fixtures';

// MOCK backend (src/test/fakeBackend.ts mirrors GET /api/admin/familias/{id}/deportistas).
// Real-backend evidence lives in e2e/vinculos-consulta.real.spec.ts.
let backend: FakeBackend;

beforeEach(() => {
  localStorage.clear();
  sessionStorage.clear();
  backend = createBackend();
});

const location = () => screen.getByTestId('location').textContent ?? '';
const athleteGets = (familyId: string) => backend.requests.filter((r) => r.method === 'get' && r.url === `/admin/familias/${familyId}/deportistas`);
const adminRequests = () => backend.requests.filter((r) => r.url.startsWith('/admin/'));
const nonGets = () => backend.requests.filter((r) => r.method !== 'get' && !r.url.startsWith('/auth/'));
const familyPath = (id: string) => `/admin/familias/${id}`;

function open(path: string) {
  backend.signIn('marcos@example.com');
  return renderApp(path);
}

const section = async () => screen.findByRole('region', { name: 'Deportistas vinculados' });
const groupItems = (name: string) => within(screen.getByRole('list', { name })).getAllByRole('listitem');

/** A family with a principal athlete, a second active one that is not the principal, an inactive athlete and a revoked link. */
function seedFamily(familyOver: { activa?: boolean } = {}) {
  const family = backend.addFamily('Familia Gómez', familyOver);
  const lucia = seedAthlete(backend);
  const bruno = backend.addAdminAthlete({ nombre: 'Bruno', apellido: 'Abad', dni: '30222333' });
  const carla = backend.addAdminAthlete({ nombre: 'Carla', apellido: 'Zárate', dni: '30444555', activo: false });
  const dario = backend.addAdminAthlete({ nombre: 'Darío', apellido: 'Moreno', dni: '30666777' });
  const luciaLink = backend.addAdminLink(family.id, lucia.id, { estado: 'ACTIVO', esPrincipal: true });
  backend.addAdminLink(family.id, bruno.id, { estado: 'ACTIVO', esPrincipal: false });
  backend.addAdminLink(family.id, carla.id, { estado: 'ACTIVO', esPrincipal: true });
  backend.addAdminLink(family.id, dario.id, { estado: 'REVOCADO' });
  return { family, lucia, bruno, carla, dario, luciaLink };
}

describe('athletes of a family: what the administrator sees', () => {
  it('has its own section, after the tutors, with the athletes linked to the family', async () => {
    const { family } = seedFamily();
    backend.addTutorBehindTheScenes(family.id, { nombre: 'Ana', apellido: 'Gómez' });
    open(familyPath(family.id));

    const athletes = await section();
    expect(within(athletes).getByRole('heading', { level: 2, name: 'Deportistas vinculados' })).toBeInTheDocument();
    const tutors = screen.getByRole('region', { name: 'Tutores' });
    expect(tutors.compareDocumentPosition(athletes) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expect(within(athletes).getByText('Solo un vínculo activo les da acceso a los deportistas.')).toBeInTheDocument();
    expect(athleteGets(family.id)).toHaveLength(1);
  });

  it('groups active links apart from the previous ones; each athlete is a link to its administrative detail, in the order of the server', async () => {
    const { family, lucia, bruno, carla, dario } = seedFamily();
    open(familyPath(family.id));
    await section();
    await screen.findByRole('heading', { level: 3, name: 'Vínculos activos' });

    expect(screen.getByRole('heading', { level: 3, name: 'Vínculos anteriores' })).toBeInTheDocument();
    const active = groupItems('Vínculos activos');
    expect(active.map((i) => within(i).getByRole('link').textContent)).toEqual(['Bruno Abad', 'Lucía Sintética', 'Carla Zárate']);
    expect(within(active[0]!).getByRole('link')).toHaveAttribute('href', `/admin/deportistas/${bruno.id}`);
    expect(within(active[1]!).getByRole('link')).toHaveAttribute('href', `/admin/deportistas/${lucia.id}`);
    expect(within(active[2]!).getByRole('link')).toHaveAttribute('href', `/admin/deportistas/${carla.id}`);
    const previous = groupItems('Vínculos anteriores');
    expect(previous.map((i) => within(i).getByRole('link').textContent)).toEqual(['Darío Moreno']);
    expect(within(previous[0]!).getByRole('link')).toHaveAttribute('href', `/admin/deportistas/${dario.id}`);
    for (const item of active) expect(within(item).getByText('Vínculo activo')).toBeInTheDocument();
    expect(within(previous[0]!).getByText('Vínculo revocado')).toBeInTheDocument();
  });

  it('marks «Principal de este deportista» only on active principal links, and «Deportista inactivo» only on inactive athletes', async () => {
    const { family } = seedFamily();
    open(familyPath(family.id));
    await screen.findByRole('heading', { level: 3, name: 'Vínculos activos' });

    const [bruno, lucia, carla] = groupItems('Vínculos activos') as [HTMLElement, HTMLElement, HTMLElement];
    expect(within(bruno).queryByText('Principal de este deportista')).not.toBeInTheDocument();
    expect(within(bruno).queryByText('Deportista inactivo')).not.toBeInTheDocument();
    expect(within(lucia).getByText('Principal de este deportista')).toBeInTheDocument();
    expect(within(lucia).queryByText('Deportista inactivo')).not.toBeInTheDocument();
    // Inactive athlete, active principal link: both facts, each with its own text, and the link stays «activo» (U1).
    expect(within(carla).getByText('Principal de este deportista')).toBeInTheDocument();
    expect(within(carla).getByText('Deportista inactivo')).toBeInTheDocument();
    expect(within(carla).getByText('Vínculo activo')).toBeInTheDocument();
    const previous = within(groupItems('Vínculos anteriores')[0]!);
    expect(previous.queryByText(/principal/i)).not.toBeInTheDocument();
    expect(screen.getAllByText('Principal de este deportista')).toHaveLength(2);
    expect(document.body).not.toHaveTextContent(/tutor principal|familia principal/i);
  });

  it('an inactive athlete behind a REVOKED link is still marked as inactive', async () => {
    const family = backend.addFamily('Familia Gómez');
    const carla = backend.addAdminAthlete({ nombre: 'Carla', apellido: 'Zárate', dni: '30444555', activo: false });
    backend.addAdminLink(family.id, carla.id, { estado: 'REVOCADO' });
    open(familyPath(family.id));

    await screen.findByRole('heading', { level: 3, name: 'Vínculos anteriores' });
    expect(within(groupItems('Vínculos anteriores')[0]!).getByText('Deportista inactivo')).toBeInTheDocument();
  });

  it.each([
    ['PENDIENTE', 'Vínculo pendiente'],
    ['RECHAZADO', 'Vínculo rechazado'],
  ] as const)('a %s link is written as «%s» among the previous ones', async (estado, text) => {
    const family = backend.addFamily('Familia Gómez');
    const eva = backend.addAdminAthlete({ nombre: 'Eva', apellido: 'Gómez', dni: '30222333' });
    backend.addAdminLink(family.id, eva.id, { estado });
    open(familyPath(family.id));

    await screen.findByRole('heading', { level: 3, name: 'Vínculos anteriores' });
    expect(within(groupItems('Vínculos anteriores')[0]!).getByText(text)).toBeInTheDocument();
    expect(screen.queryByRole('heading', { level: 3, name: 'Vínculos activos' })).not.toBeInTheDocument();
  });

  it('a family with no athletes says so, with nothing to create', async () => {
    const family = backend.addFamily('Familia Vacía');
    open(familyPath(family.id));

    const athletes = await section();
    expect(await within(athletes).findByText('Esta familia todavía no tiene deportistas vinculados.')).toBeInTheDocument();
    expect(within(athletes).queryByRole('list')).not.toBeInTheDocument();
    expect(within(athletes).queryByRole('heading', { level: 3 })).not.toBeInTheDocument();
    expect(within(athletes).getByRole('button', { name: 'Vincular deportistas' })).toBeEnabled();
    expect(within(athletes).queryByRole('link')).not.toBeInTheDocument();
  });

  it('only previous links: says there are no active ones and still lists the history', async () => {
    const family = backend.addFamily('Familia Gómez');
    const eva = backend.addAdminAthlete({ nombre: 'Eva', apellido: 'Gómez', dni: '30222333' });
    backend.addAdminLink(family.id, eva.id, { estado: 'REVOCADO' });
    open(familyPath(family.id));

    const athletes = await section();
    expect(await within(athletes).findByText('Esta familia no tiene deportistas con vínculo activo.')).toBeInTheDocument();
    expect(within(athletes).getByRole('heading', { level: 3, name: 'Vínculos anteriores' })).toBeInTheDocument();
    expect(within(athletes).queryByText('Esta familia todavía no tiene deportistas vinculados.')).not.toBeInTheDocument();
  });

  it('an inactive family says it does not enter the portal even with active links; an active one does not say it', async () => {
    const { family } = seedFamily({ activa: false });
    open(familyPath(family.id));
    await screen.findByRole('heading', { level: 3, name: 'Vínculos activos' });
    expect(screen.getByText('La familia está inactiva: no entra al portal aunque sus vínculos sigan activos.')).toBeInTheDocument();
    expect(within(groupItems('Vínculos activos')[0]!).getByText('Vínculo activo')).toBeInTheDocument();
  });

  it('an active family does not carry the inactive-family note', async () => {
    const { family } = seedFamily();
    open(familyPath(family.id));
    await screen.findByRole('heading', { level: 3, name: 'Vínculos activos' });
    expect(screen.queryByText(/no entra al portal/)).not.toBeInTheDocument();
  });

  it('asks for no DNI or CUIL, draws none and never draws the revision', async () => {
    const { family } = seedFamily();
    open(familyPath(family.id));
    await screen.findByRole('heading', { level: 3, name: 'Vínculos activos' });

    const text = (await section()).textContent ?? '';
    for (const secret of [DNI, CUIL, '30222333', 'Calle Falsa', String(REVISION)]) expect(text, secret).not.toContain(secret);
    expect(text.toLowerCase()).not.toContain('dni');
    expect(text.toLowerCase()).not.toContain('cuil');
    expect(text.toLowerCase()).not.toMatch(/revisi|versi/);
    expect(location()).toBe(familyPath(family.id));
  });
});

describe('athletes of a family: loading, error and retry', () => {
  it('shows a loading state in the section while the answer is on its way, with the rest of the family already there', async () => {
    const { family } = seedFamily();
    backend.signIn('marcos@example.com');
    const held = backend.hold('get', `/admin/familias/${family.id}/deportistas`);
    renderApp(familyPath(family.id));

    expect(await screen.findByText('Cargando los deportistas vinculados…')).toBeInTheDocument();
    expect(screen.getByRole('heading', { level: 1, name: 'Familia Gómez' })).toBeInTheDocument();
    held.release();
    expect(await screen.findByRole('heading', { level: 3, name: 'Vínculos activos' })).toBeInTheDocument();
    expect(screen.queryByText('Cargando los deportistas vinculados…')).not.toBeInTheDocument();
  });

  it('a failure breaks only the section: fixed message, «Reintentar», and the family detail stays usable', async () => {
    const { family } = seedFamily();
    backend.addTutorBehindTheScenes(family.id, { nombre: 'Ana', apellido: 'Gómez' });
    backend.override('get', `/admin/familias/${family.id}/deportistas`, { status: 500, body: { codigo: 'X', mensaje: 'detalle interno del servidor' } });
    open(familyPath(family.id));
    const user = userEvent.setup();

    const athletes = await section();
    expect(await within(athletes).findByRole('alert')).toHaveTextContent('Ocurrió un problema en el servidor. Intentá de nuevo en unos minutos.');
    expect(document.body).not.toHaveTextContent('detalle interno del servidor');
    expect(screen.getByRole('heading', { level: 1, name: 'Familia Gómez' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Ana Gómez' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Editar nombre' })).toBeInTheDocument();
    await user.click(within(athletes).getByRole('button', { name: 'Reintentar' }));

    expect(await screen.findByRole('heading', { level: 3, name: 'Vínculos activos' })).toBeInTheDocument();
    expect(within(athletes).queryByRole('alert')).not.toBeInTheDocument();
    expect(athleteGets(family.id)).toHaveLength(2);
  });

  it('a 403 in the section says there is no permission', async () => {
    const { family } = seedFamily();
    backend.override('get', `/admin/familias/${family.id}/deportistas`, { status: 403, body: { codigo: 'ACCESO_DENEGADO', mensaje: 'x' } });
    open(familyPath(family.id));

    expect(await within(await section()).findByRole('alert')).toHaveTextContent('No tenés permiso para ver los deportistas vinculados.');
    expect(screen.getByRole('heading', { level: 1, name: 'Familia Gómez' })).toBeInTheDocument();
  });

  it.each([404, 400])('a %s in the section (the detail did load) is a fixed error with retry, never the backend message', async (status) => {
    const { family } = seedFamily();
    backend.override('get', `/admin/familias/${family.id}/deportistas`, { status, body: { codigo: 'FAMILIA_NO_ENCONTRADA', mensaje: 'secreto del servidor' } });
    open(familyPath(family.id));

    const athletes = await section();
    expect(await within(athletes).findByRole('alert')).toHaveTextContent('No pudimos cargar los deportistas vinculados. Intentá de nuevo.');
    expect(within(athletes).getByRole('button', { name: 'Reintentar' })).toBeInTheDocument();
    expect(document.body).not.toHaveTextContent('secreto del servidor');
  });

  it('a malformed answer or a row of another family is a fixed error and nothing of it is drawn', async () => {
    const { family } = seedFamily();
    backend.override('get', `/admin/familias/${family.id}/deportistas`, {
      status: 200,
      body: [rawLink({ familiaId: family.id, deportistaNombre: 'Propia' }), rawLink({ familiaId: 'otra-familia', deportistaNombre: 'Ajena', vinculoId: 'vin-y' })],
    });
    open(familyPath(family.id));

    expect(await within(await section()).findByRole('alert')).toHaveTextContent('Ocurrió un problema en el servidor. Intentá de nuevo en unos minutos.');
    expect(document.body).not.toHaveTextContent('Ajena');
    expect(document.body).not.toHaveTextContent('Propia');
  });

  it('a REFRESH that fails does not take the athletes off the screen', async () => {
    const { family } = seedFamily();
    const { queryClient } = open(familyPath(family.id));
    await screen.findByRole('heading', { level: 3, name: 'Vínculos activos' });

    backend.override('get', `/admin/familias/${family.id}/deportistas`, 'network');
    await act(async () => {
      await queryClient.refetchQueries({ queryKey: ['admin', 'family-athletes', family.id] });
    });

    expect(athleteGets(family.id)).toHaveLength(2);
    expect(screen.getByText('Lucía Sintética')).toBeInTheDocument();
    expect(within(await section()).queryByRole('alert')).not.toBeInTheDocument();
  });

  it('a refresh that works shows a link revoked in the meantime among the previous ones', async () => {
    const { family, luciaLink } = seedFamily();
    const { queryClient } = open(familyPath(family.id));
    await screen.findByRole('heading', { level: 3, name: 'Vínculos activos' });

    backend.changeAdminLinkBehindTheScenes(luciaLink.id, { estado: 'REVOCADO' });
    await act(async () => {
      await queryClient.refetchQueries({ queryKey: ['admin', 'family-athletes', family.id] });
    });

    await waitFor(() => expect(groupItems('Vínculos activos').map((i) => within(i).getByRole('link').textContent)).toEqual(['Bruno Abad', 'Carla Zárate']));
    expect(groupItems('Vínculos anteriores').map((i) => within(i).getByRole('link').textContent)).toEqual(['Darío Moreno', 'Lucía Sintética']);
    expect(screen.getAllByText('Principal de este deportista')).toHaveLength(1);
  });
});

describe('athletes of a family: navigation', () => {
  it('goes from the family to an athlete and from the athlete back to the family through the links of both', async () => {
    const { family, lucia } = seedFamily();
    open(familyPath(family.id));
    const user = userEvent.setup();

    await screen.findByRole('heading', { level: 3, name: 'Vínculos activos' });
    await user.click(screen.getByRole('link', { name: 'Lucía Sintética' }));
    expect(location()).toBe(`/admin/deportistas/${lucia.id}`);
    expect(await screen.findByRole('heading', { level: 1, name: 'Lucía Sintética' })).toBeInTheDocument();
    await user.click(screen.getByRole('link', { name: 'Ver familias vinculadas' }));
    expect(location()).toBe(`/admin/deportistas/${lucia.id}/vinculos`);
    await user.click(await screen.findByRole('link', { name: 'Familia Gómez' }));
    expect(location()).toBe(familyPath(family.id));
    expect(await screen.findByRole('heading', { level: 1, name: 'Familia Gómez' })).toBeInTheDocument();
    expect(await screen.findByRole('heading', { level: 3, name: 'Vínculos activos' })).toBeInTheDocument();
  });
});

describe('athletes of a family: read-only consultation and private opt-in linking', () => {
  it('the closed selector only offers linking and consultation still sends no writes', async () => {
    const { family } = seedFamily();
    open(familyPath(family.id));
    await screen.findByRole('heading', { level: 3, name: 'Vínculos activos' });

    const athletes = within(await section());
    expect(athletes.getAllByRole('button').map((b) => b.textContent)).toEqual(['Vincular deportistas']);
    expect(athletes.queryAllByRole('checkbox')).toEqual([]);
    expect(athletes.queryAllByRole('textbox')).toEqual([]);
    expect(athletes.queryAllByRole('combobox')).toEqual([]);
    expect(athletes.getAllByRole('link').map((l) => l.textContent)).toEqual(['Bruno Abad', 'Lucía Sintética', 'Carla Zárate', 'Darío Moreno']);
    expect(nonGets()).toEqual([]);
    expect(backend.writes()).toEqual([]);
  });

  it('asks only for the family and its athletes, with no personal data in any address', async () => {
    const { family } = seedFamily();
    open(familyPath(family.id));
    await screen.findByRole('heading', { level: 3, name: 'Vínculos activos' });

    const allowed = [new RegExp(`^/admin/familias/${family.id}$`), new RegExp(`^/admin/familias/${family.id}/deportistas$`)];
    for (const request of adminRequests()) {
      expect(request.method).toBe('get');
      expect(allowed.some((pattern) => pattern.test(request.url)), request.url).toBe(true);
      for (const secret of [DNI, CUIL, 'Lucía']) expect(request.url).not.toContain(secret);
    }
  });

  it('a family that does not exist is the «not found» screen and its athletes are never requested', async () => {
    open(familyPath('fam-inexistente'));
    expect(await screen.findByRole('heading', { name: 'No encontramos esta familia' })).toBeInTheDocument();
    await waitFor(() => expect(adminRequests().length).toBeGreaterThan(0));
    expect(athleteGets('fam-inexistente')).toHaveLength(0);
  });

  it('an anonymous visitor never sees nor requests the athletes of a family', async () => {
    const { family } = seedFamily();
    renderApp(familyPath(family.id));
    expect(await screen.findByRole('heading', { name: 'Acceso administrativo' })).toBeInTheDocument();
    expect(adminRequests()).toHaveLength(0);
  });

  it('a family user never sees nor requests the athletes of a family', async () => {
    const { family } = seedFamily();
    backend.signIn('lucia@example.com');
    renderApp(familyPath(family.id));
    expect(await screen.findByRole('heading', { name: 'No tenés permiso para ver esta sección' })).toBeInTheDocument();
    expect(adminRequests()).toHaveLength(0);
    expect(document.body).not.toHaveTextContent('Lucía Sintética');
  });
});
